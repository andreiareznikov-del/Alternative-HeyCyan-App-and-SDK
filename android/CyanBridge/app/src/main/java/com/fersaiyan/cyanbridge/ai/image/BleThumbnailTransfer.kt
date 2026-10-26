package com.fersaiyan.cyanbridge.ai.image

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** One owner of the SDK slot, including settling, validation, and the optional retry. */
internal class BleThumbnailTransfer(
    private val transport: Transport,
    private val connectionReady: () -> Boolean,
    private val requireReconnect: () -> Unit,
    private val validateJpeg: (File) -> Boolean,
    private val clockMs: () -> Long,
    private val log: (String) -> Unit,
    private val timeoutMs: Long = 20_000L,
    private val settleMs: Long = 250L,
    private val retryDelayMs: Long = 300L,
) {
    interface Transport {
        fun request(callback: (Int, Boolean, ByteArray?) -> Unit)
        /** Detach only this request's callback; never clear the SDK's other response slots. */
        fun detach()
    }

    suspend fun receive(
        directory: File,
        filenamePrefix: String,
        photoReadyAtMs: Long,
        releasePermit: () -> Unit,
    ): File? {
        try {
            check(photoReadyAtMs > 0L) { "No confirmed photo-ready notification" }
            val waitMs = (photoReadyAtMs + settleMs - clockMs()).coerceAtLeast(0L)
            log("photoReadyAgeMs=${clockMs() - photoReadyAtMs} settleWaitMs=$waitMs")
            delay(waitMs)
            yield() // Dispatch even when the photo was already settled at coroutine entry.
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory" }
            for (number in 1..2) {
                currentCoroutineContext().ensureActive()
                if (!connectionReady()) {
                    log("attempt=$number skipped: BLE disconnected, permit expired, or reconnect required")
                    return null
                }
                val file = File(directory, "${filenamePrefix}_attempt$number.jpg")
                val attempt = Attempt(file, number)
                var requested = false
                var keepFile = false
                try {
                    requested = true
                    log("attempt=$number/2 SDK=getPictureThumbnails connected=${connectionReady()} file=${file.absolutePath}")
                    transport.request(attempt::onData)
                    val received = withTimeoutOrNull(timeoutMs) { attempt.result.await() }
                    // Close the sink BEFORE detaching or validating. Even a retained callback is inert.
                    val snapshot = attempt.close()
                    transport.detach()
                    log("attempt=$number end=${if (received == null) "timeout" else "callback"} " +
                        "chunks=${snapshot.chunks} callbacks=${snapshot.callbacks} bytes=${snapshot.bytes} " +
                        "complete=${snapshot.complete} error=${snapshot.error} " +
                        "elapsedMs=${clockMs() - attempt.startedAtMs} connected=${connectionReady()}")
                    if (received == null || !snapshot.complete) {
                        // 0xFD frames carry no transaction ID. An unfinished request cannot safely
                        // be replaced with another 0xFD callback in the same BLE connection.
                        requireReconnect()
                        log("attempt=$number reconnectRequired=true; no unsafe timeout retry")
                        return null
                    }
                    val valid = snapshot.error == null && snapshot.bytes > 0 &&
                        connectionReady() && validateJpeg(file)
                    log("attempt=$number jpegDecoded=$valid bytes=${file.length()}")
                    if (valid) {
                        keepFile = true
                        return file
                    }
                } catch (cancelled: CancellationException) {
                    if (requested && !attempt.close().complete) requireReconnect()
                    log("attempt=$number cancelled")
                    throw cancelled
                } catch (error: Exception) {
                    if (requested && !attempt.close().complete) requireReconnect()
                    log("attempt=$number exception=${error.javaClass.simpleName}: ${error.message}")
                    return null
                } finally {
                    attempt.close()
                    try {
                        transport.detach()
                    } finally {
                        if (!keepFile) file.delete()
                    }
                }
                if (number < 2) {
                    log("attempt=$number completed with invalid JPEG; retryDelayMs=$retryDelayMs")
                    delay(retryDelayMs)
                }
            }
            return null
        } finally {
            releasePermit()
        }
    }

    private data class Snapshot(
        val callbacks: Int,
        val chunks: Int,
        val bytes: Long,
        val complete: Boolean,
        val error: String?,
    )

    private inner class Attempt(file: File, private val number: Int) {
        val startedAtMs = clockMs()
        val result = CompletableDeferred<Snapshot>()
        private val sink = FileOutputStream(file, false)
        private var closed = false
        private var callbacks = 0
        private var chunks = 0
        private var bytes = 0L
        private var complete = false
        private var error: String? = null

        @Synchronized
        fun onData(packetType: Int, isComplete: Boolean, data: ByteArray?) {
            if (closed) {
                log("attempt=$number lateCallbackIgnored=true complete=$isComplete")
                return
            }
            callbacks++
            complete = isComplete
            try {
                check((packetType and 0xFF) == 0xFD) { "Unexpected thumbnail packet type $packetType" }
                if (data != null && data.isNotEmpty()) {
                    check(bytes + data.size <= 8L * 1024 * 1024) { "Preview exceeds 8 MiB" }
                    sink.write(data)
                    chunks++
                    bytes += data.size
                }
            } catch (failure: Exception) {
                error = "${failure.javaClass.simpleName}: ${failure.message}"
            }
            if (callbacks == 1 || callbacks % 32 == 0 || complete || error != null) {
                log("attempt=$number callback=$callbacks packetType=$packetType chunks=$chunks " +
                    "bytes=$bytes complete=$complete elapsedMs=${clockMs() - startedAtMs}")
            }
            if (complete || error != null) result.complete(close())
        }

        @Synchronized
        fun close(): Snapshot {
            if (!closed) {
                closed = true
                try {
                    sink.close()
                } catch (failure: Exception) {
                    error = "Close failed: ${failure.message}"
                }
            }
            return Snapshot(callbacks, chunks, bytes, complete, error)
        }
    }
}
