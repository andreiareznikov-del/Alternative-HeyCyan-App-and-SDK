package com.fersaiyan.cyanbridge.ai.image

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class BleThumbnailTransferTest {
    @get:Rule val temporary = TemporaryFolder()

    private class FakeTransport : BleThumbnailTransfer.Transport {
        val callbacks = mutableListOf<(Int, Boolean, ByteArray?) -> Unit>()
        var detached = 0
        var request: (Int, (Int, Boolean, ByteArray?) -> Unit) -> Unit = { _, _ -> }
        override fun request(callback: (Int, Boolean, ByteArray?) -> Unit) {
            callbacks += callback
            request(callbacks.size, callback)
        }
        override fun detach() { detached++ }
    }

    private inner class Harness(scope: TestScope) {
        val directory = temporary.newFolder()
        val transport = FakeTransport()
        val logs = mutableListOf<String>()
        var released = 0
        var reconnects = 0
        var connected = true
        var validator: (File) -> Boolean = { it.readBytes().contentEquals(byteArrayOf(1, 2, 3)) }
        val receiver = BleThumbnailTransfer(
            transport, { connected }, { reconnects++ }, { validator(it) },
            { scope.testScheduler.currentTime + 1L }, logs::add,
            timeoutMs = 1_000L,
        )
        suspend fun receive(readyAtMs: Long = 1L): File? = receiver.receive(directory, "preview", readyAtMs) { released++ }
    }

    @Test fun waitsAfterPhotoReadyAndReleasesOnceOnSuccess() = runTest {
        val h = Harness(this)
        h.transport.request = { _, callback -> callback(253, true, byteArrayOf(1, 2, 3)) }
        val job = async { h.receive() }
        advanceTimeBy(249)
        runCurrent()
        assertTrue(h.transport.callbacks.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertArrayEquals(byteArrayOf(1, 2, 3), job.await()!!.readBytes())
        assertEquals(1, h.released)
        assertEquals(0, h.reconnects)
    }

    @Test fun completedInvalidImageRetriesInSeparateFileAndIgnoresOldCallback() = runTest {
        val h = Harness(this)
        val validatedFiles = mutableListOf<String>()
        h.validator = { file -> validatedFiles += file.name; file.readBytes().contentEquals(byteArrayOf(1, 2, 3)) }
        h.transport.request = { number, callback ->
            assertEquals(0, h.released)
            if (number == 1) callback(253, true, byteArrayOf(9))
            else {
                assertTrue(h.transport.detached > 0)
                h.transport.callbacks[0](253, true, byteArrayOf(99))
                callback(253, false, byteArrayOf(1, 2))
                callback(253, true, byteArrayOf(3))
            }
        }
        val file = h.receive()!!
        assertEquals(listOf("preview_attempt1.jpg", "preview_attempt2.jpg"), validatedFiles)
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertFalse(File(h.directory, "preview_attempt1.jpg").exists())
        assertEquals(1, h.directory.listFiles()!!.size)
        assertEquals(1, h.released)
        assertEquals(0, h.reconnects)
    }

    @Test fun timeoutClosesSinkDetachesAndRequiresReconnectWithoutBlindRetry() = runTest {
        val h = Harness(this)
        h.transport.request = { _, callback -> callback(253, false, byteArrayOf(1)) }
        assertNull(h.receive())
        assertEquals(1, h.transport.callbacks.size)
        assertEquals(1, h.reconnects)
        assertEquals(1, h.released)
        assertTrue(h.transport.detached > 0)
        h.transport.callbacks.single()(253, true, byteArrayOf(2, 3))
        assertTrue(h.directory.listFiles()!!.isEmpty())
    }

    @Test fun cancellationDuringTransferReleasesOnceAndLateCallbackCannotRecreateFile() = runTest {
        val h = Harness(this)
        val job = async { h.receive() }
        advanceTimeBy(250)
        runCurrent()
        h.transport.callbacks.single()(253, false, byteArrayOf(1))
        job.cancel()
        runCurrent()
        h.transport.callbacks.single()(253, true, byteArrayOf(2, 3))
        assertTrue(job.isCancelled)
        assertEquals(1, h.released)
        assertEquals(1, h.reconnects)
        assertTrue(h.directory.listFiles()!!.isEmpty())
    }

    @Test fun cancellationWhileSettlingReleasesWithoutSdkOrQuarantine() = runTest {
        val h = Harness(this)
        val job = async { h.receive() }
        runCurrent()
        job.cancel()
        runCurrent()
        assertEquals(1, h.released)
        assertEquals(0, h.reconnects)
        assertTrue(h.transport.callbacks.isEmpty())
    }

    @Test fun sdkExceptionStillDetachesAndReleases() = runTest {
        val h = Harness(this)
        h.transport.request = { _, _ -> throw IllegalStateException("SDK failed after registration") }
        assertNull(h.receive())
        assertEquals(1, h.released)
        assertEquals(1, h.reconnects)
        assertTrue(h.transport.detached > 0)
        assertTrue(h.directory.listFiles()!!.isEmpty())
    }

    @Test fun disconnectedDeviceIsNotAskedForAThumbnail() = runTest {
        val h = Harness(this)
        h.connected = false
        assertNull(h.receive())
        assertEquals(1, h.released)
        assertTrue(h.transport.callbacks.isEmpty())
    }

    @Test fun disconnectInvalidatesEvenAnOtherwiseValidCompletedResponse() = runTest {
        val h = Harness(this)
        h.transport.request = { _, callback ->
            callback(253, true, byteArrayOf(1, 2, 3))
            h.connected = false
        }
        assertNull(h.receive())
        assertEquals(1, h.released)
        assertEquals(1, h.transport.callbacks.size)
        assertTrue(h.directory.listFiles()!!.isEmpty())
    }

    @Test fun emptyCompletedTransfersStopAfterTwoAttempts() = runTest {
        val h = Harness(this)
        h.transport.request = { _, callback -> callback(253, true, null) }
        assertNull(h.receive())
        assertEquals(2, h.transport.callbacks.size)
        assertEquals(1, h.released)
        assertEquals(0, h.reconnects)
        assertTrue(h.directory.listFiles()!!.isEmpty())
    }

    @Test fun wrongPacketTypeDoesNotProduceAnImage() = runTest {
        val h = Harness(this)
        h.transport.request = { _, callback -> callback(65, false, byteArrayOf(1, 2, 3)) }
        assertNull(h.receive())
        assertEquals(1, h.reconnects)
        assertEquals(1, h.released)
    }

    @Test fun decoderExceptionReleasesAndDeletesFailedFile() = runTest {
        val h = Harness(this)
        h.validator = { throw IllegalArgumentException("decoder failed") }
        h.transport.request = { _, callback -> callback(253, true, byteArrayOf(1, 2, 3)) }
        assertNull(h.receive())
        assertEquals(1, h.released)
        assertTrue(h.directory.listFiles()!!.isEmpty())
    }

    @Test fun missingPhotoReadyNeverReadsStaleSdkImage() = runTest {
        val h = Harness(this)
        try {
            h.receive(readyAtMs = 0)
            fail("Missing photo-ready must fail")
        } catch (_: IllegalStateException) {
            assertEquals(1, h.released)
            assertTrue(h.transport.callbacks.isEmpty())
        }
    }

    @Test fun filePreparationExceptionReleasesPermit() = runTest {
        val h = Harness(this)
        File(h.directory, "preview_attempt1.jpg").mkdir()
        try {
            h.receive()
            fail("A directory cannot be opened as an image")
        } catch (_: java.io.FileNotFoundException) {
            assertEquals(1, h.released)
            assertTrue(h.transport.callbacks.isEmpty())
        }
    }
}
