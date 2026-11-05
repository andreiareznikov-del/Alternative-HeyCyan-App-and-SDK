package com.fersaiyan.cyanbridge.w610chatgpt

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fersaiyan.cyanbridge.ai.image.BleThumbnailTransfer
import com.fersaiyan.cyanbridge.ai.image.HeyCyanThumbnailTransport
import com.fersaiyan.cyanbridge.ai.image.ThumbnailJpegValidator
import com.fersaiyan.cyanbridge.ai.image.W610PreviewStore
import com.fersaiyan.cyanbridge.glasses.GlassesSessionCoordinator
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

/** BLE lifetime belongs to this service, not an Activity. No microphone/audio/media actions. */
@SuppressLint("MissingPermission")
class W610BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = OperationGate()
    private var registered = false
    private var wanted = false
    private var ready = false
    private var epoch = 0L
    private var address: String? = null // Private in-memory connection selector; never logged.
    private var connecting: CompletableDeferred<Boolean>? = null
    private var photoReady: CompletableDeferred<Long>? = null
    private var operation: Job? = null
    private var reconnect: Job? = null
    private var watchdog: Job? = null
    private val manager get() = BleOperateManager.getInstance()
    private val sdk get() = LargeDataHandler.getInstance()

    private val receiver = object : QCBluetoothCallbackCloneReceiver() {
        override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
            if (!connected) scope.launch { lostConnection() }
        }
        override fun onServiceDiscovered() {
            scope.launch {
                if (!wanted || !hasNearby() || !manager.isConnected) return@launch
                try {
                    sdk.initEnable()
                    manager.isReady = true
                    ready = true
                    connecting?.complete(true)
                    report("BLE_CONNECTED", "W610 подключены; можно открыть ChatGPT")
                } catch (_: Exception) { lostConnection() }
            }
        }
    }
    private val photoListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            val data = response.loadData
            val event = data.getOrNull(6)?.toInt()?.and(255)
            val subtype = data.getOrNull(7)?.toInt()?.and(255)
            val arrived = SystemClock.elapsedRealtime()
            val observedEpoch = epoch
            scope.launch {
                if (!ready || !wanted || observedEpoch != epoch) return@launch
                when {
                    event == 3 && subtype == 1 -> startPhoto("HARDWARE_BUTTON", null)
                    event == 2 -> {
                        val current = gate.active
                        if (current == null) startPhoto("READY_ONLY", arrived)
                        else if (gate.ready() != null) {
                            BridgeJournal.record(this@W610BridgeService, current.id, "PHOTO_READY")
                            photoReady?.complete(arrived)
                        } else BridgeJournal.record(this@W610BridgeService, current.id, "DUPLICATE_READY_IGNORED")
                    }
                    // Multimedia and all unrelated vendor events intentionally have no action.
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            wanted = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasNearby()) {
            BridgeJournal.record(this, "service", "ERROR", "NEARBY_PERMISSION_MISSING")
            BridgeJournal.status(this, "Нет разрешения на ближайшие устройства")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "W610 BLE", NotificationManager.IMPORTANCE_LOW)
            )
            startForeground(NOTIFICATION, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            initializeSdk()
            when (intent?.action) {
                CONNECT -> {
                    val selected = intent.getStringExtra("device")
                    if (selected == null || !android.bluetooth.BluetoothAdapter.checkBluetoothAddress(selected)) {
                        BridgeJournal.record(this, "service", "ERROR", "DEVICE_SELECTION_INVALID")
                        stopSelf()
                    } else if (wanted && (ready || reconnect?.isActive == true)) {
                        BridgeJournal.record(this, "service", "DEVICE_SELECTION_IGNORED", "STOP_BEFORE_RESELECT")
                    } else {
                        address = selected
                        wanted = true
                        scheduleReconnect()
                        startWatchdog()
                    }
                }
                CAPTURE -> startPhoto("APP_BUTTON", null)
            }
        } catch (_: SecurityException) {
            BridgeJournal.record(this, "service", "ERROR", "FOREGROUND_SERVICE_PERMISSION_REJECTED")
            stopSelf()
        } catch (_: RuntimeException) {
            BridgeJournal.record(this, "service", "ERROR", "SERVICE_START_FAILED")
            stopSelf()
        }
        // A process kill requires a deliberate user restart, never a background launch loop.
        return START_NOT_STICKY
    }

    private fun hasNearby() = NEARBY.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun initializeSdk() {
        if (registered) return
        if (!sdkInitialized) {
            BleOperateManager.getInstance(application).setApplication(application)
            BleBaseControl.getInstance(application).setmContext(application)
            manager.init()
            sdkInitialized = true
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, BleAction.getIntentFilter())
        sdk.addOutDeviceListener(LISTENER, photoListener)
        registered = true
    }

    private fun scheduleReconnect() {
        if (!wanted || ready || reconnect?.isActive == true) return
        reconnect = scope.launch {
            operation?.cancelAndJoin() // Finish closing the old 0xFD slot before reusing the SDK.
            for (attempt in 1..5) {
                if (!wanted || !hasNearby() || ready) return@launch
                BridgeJournal.record(this@W610BridgeService, "service", "BLE_CONNECT_ATTEMPT", value = attempt.toLong())
                report("BLE_CONNECTING", "Подключение W610, попытка $attempt из 5")
                val waiter = CompletableDeferred<Boolean>()
                connecting = waiter
                try {
                    DeviceManager.getInstance().deviceName = "W610"
                    DeviceManager.getInstance().deviceAddress = address ?: return@launch
                    manager.connectDirectly(address)
                    if (withTimeoutOrNull(25_000) { waiter.await() } == true) return@launch
                } catch (_: SecurityException) {
                    report("ERROR", "Разрешение Bluetooth отозвано")
                    wanted = false
                    stopSelf()
                    return@launch
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { BridgeJournal.record(this@W610BridgeService, "service", "ERROR", "BLE_CONNECT_FAILED")
                } finally { if (connecting === waiter) connecting = null }
                runCatching { manager.disconnect() }
                GlassesSessionCoordinator.clearForDisconnectedDevice()
                delay(minOf(2_000L shl (attempt - 1), 30_000))
            }
            wanted = false
            report("ERROR", "W610 недоступны после 5 попыток. Подключите снова кнопкой приложения")
        }
    }

    private fun startWatchdog() {
        if (watchdog?.isActive == true) return
        watchdog = scope.launch {
            while (wanted) {
                delay(2_000)
                if (!hasNearby()) {
                    wanted = false
                    BridgeJournal.record(this@W610BridgeService, "service", "ERROR", "NEARBY_PERMISSION_REVOKED")
                    stopSelf()
                } else if (ready && !manager.isConnected) lostConnection()
                else if (!ready && reconnect?.isActive != true) scheduleReconnect()
            }
        }
    }

    private fun lostConnection() {
        if (!registered) return
        epoch++
        ready = false
        connecting?.complete(false)
        operation?.cancel()
        photoReady?.cancel()
        GlassesSessionCoordinator.clearForDisconnectedDevice()
        report("BLE_DISCONNECTED", "BLE отключён; ожидается переподключение")
        scheduleReconnect()
    }

    private fun startPhoto(origin: String, confirmedReadyAt: Long?) {
        if (!ready || !hasNearby() || !manager.isConnected) {
            BridgeJournal.record(this, "service", "ERROR", "PHOTO_REQUIRES_BLE")
            return
        }
        val current = gate.begin(UUID.randomUUID().toString(), origin, SystemClock.elapsedRealtime())
        if (current == null) {
            BridgeJournal.record(this, gate.active?.id ?: "service", "DUPLICATE_TRIGGER_IGNORED")
            return
        }
        val id = current.id
        BridgeJournal.record(this, id, "OPERATION_STARTED", origin)
        if (origin == "HARDWARE_BUTTON") BridgeJournal.record(this, id, "CAMERA_BUTTON_RECEIVED")
        BridgeJournal.setLatest(this, id)
        val permit = GlassesSessionCoordinator.tryAcquireBackgroundCommand()
        if (permit == null || !GlassesSessionCoordinator.canRequestThumbnail(permit)) {
            if (permit != null) GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            BridgeJournal.record(this, id, "ERROR", "SDK_OWNERSHIP_UNAVAILABLE")
            gate.finish(id, SystemClock.elapsedRealtime())
            return
        }
        val releaseOnce = AtomicBoolean(false)
        val release = { if (releaseOnce.compareAndSet(false, true)) GlassesSessionCoordinator.releaseBackgroundCommand(permit) }
        val waiter = if (confirmedReadyAt == null) CompletableDeferred<Long>() else null
        photoReady = waiter
        if (confirmedReadyAt != null) {
            gate.ready()
            BridgeJournal.record(this, id, "PHOTO_READY", "UNSOLICITED_READY_NO_BUTTON_PROOF")
        }
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var needsReconnect = false
            var transportFinished = false
            val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "W610ChatGPT:jpeg")
            try {
                wake.acquire(55_000)
                val at = confirmedReadyAt ?: run {
                    withContext(Dispatchers.IO) {
                        cameraCommand(id, "VISION", byteArrayOf(0x02, 0x01, 0x06, 4, 4))
                        delay(250)
                        cameraCommand(id, "SHUTTER", byteArrayOf(0x02, 0x01, 0x01))
                    }
                    withTimeout(5_000) { requireNotNull(waiter).await() }
                }
                photoReady = null
                BridgeJournal.record(this@W610BridgeService, id, "JPEG_TRANSFER_STARTED")
                val image = withContext(Dispatchers.IO) {
                    BleThumbnailTransfer(
                        transport = HeyCyanThumbnailTransport(),
                        connectionReady = { hasNearby() && manager.isConnected && GlassesSessionCoordinator.canRequestThumbnail(permit) },
                        requireReconnect = { needsReconnect = true; GlassesSessionCoordinator.requireThumbnailReconnect(permit) },
                        validateJpeg = ThumbnailJpegValidator::isValid,
                        clockMs = SystemClock::elapsedRealtime,
                        log = { message ->
                            // The shared logger includes paths/error text. Retain only whitelisted numeric metrics.
                            Regex("(?:attempt|chunks|callbacks|bytes|elapsedMs)=([0-9]+)").findAll(message).forEach { match ->
                                val key = match.value.substringBefore('=').uppercase()
                                BridgeJournal.record(this@W610BridgeService, id, "TRANSFER_$key", value = match.groupValues[1].toLong())
                            }
                        },
                    ).receive(filesDir, "W610_$id", at) { release() }
                }
                transportFinished = true
                if (image == null) {
                    BridgeJournal.record(this@W610BridgeService, id, "ERROR", if (needsReconnect) "JPEG_TIMEOUT_RECONNECT_REQUIRED" else "JPEG_TRANSFER_FAILED")
                    return@launch
                }
                try {
                    BridgeJournal.record(this@W610BridgeService, id, "JPEG_TRANSFER_COMPLETE", value = image.length())
                    BridgeJournal.record(this@W610BridgeService, id, "JPEG_VALIDATED")
                    val staged = withContext(Dispatchers.IO) {
                        runCatching { W610PreviewStore.save(this@W610BridgeService, image) }
                            .onFailure { BridgeJournal.record(this@W610BridgeService, id, "ERROR", "GALLERY_SAVE_FAILED") }
                        ChatGptImageHandoff.onImageReadyForQuestion(this@W610BridgeService, id, image)
                    }
                    if (staged) {
                        report("JPEG_STAGED", "JPEG готов; подтверждение ChatGPT ожидается")
                        if (BridgeJournal.autoShare(this@W610BridgeService)) {
                            ChatGptImageHandoff.triggerAssistantImageQuery(this@W610BridgeService, id)
                            scope.launch {
                                delay(15_000)
                                if ("CHATGPT_SHARE_ACCEPTED" !in BridgeJournal.events(this@W610BridgeService, id)) {
                                    BridgeJournal.record(this@W610BridgeService, id, "ERROR", "SHARE_RECEIPT_UNCONFIRMED")
                                    report("SHARE_PENDING", "ChatGPT не подтвердил открытие JPEG; проверьте журнал")
                                }
                            }
                        }
                    }
                } finally { image.delete() }
            } catch (cancelled: CancellationException) {
                if (!transportFinished) needsReconnect = true
                BridgeJournal.record(this@W610BridgeService, id, "ERROR", if (cancelled is TimeoutCancellationException) "PHOTO_READY_TIMEOUT" else "OPERATION_CANCELLED")
                if (cancelled !is TimeoutCancellationException) throw cancelled
            } catch (_: Exception) {
                if (!transportFinished) needsReconnect = true
                BridgeJournal.record(this@W610BridgeService, id, "ERROR", "CAPTURE_FAILED")
            } finally {
                photoReady = null
                release()
                if (wake.isHeld) wake.release()
                gate.finish(id, SystemClock.elapsedRealtime())
                if (needsReconnect && wanted && hasNearby()) {
                    ready = false
                    runCatching { manager.disconnect() }
                    // The vendor disconnect invalidates the old singleton response slot.
                    GlassesSessionCoordinator.clearForDisconnectedDevice()
                    scope.launch { delay(1_000); scheduleReconnect() }
                }
            }
        }
    }

    private fun cameraCommand(id: String, stage: String, bytes: ByteArray) {
        BridgeJournal.record(this, id, "CAMERA_COMMAND_SENT", stage)
        sdk.glassesControl(bytes) { _, response ->
            BridgeJournal.record(this, id, "CAMERA_ACK", stage, response.errorCode.toLong())
            // ACK error=-1 is diagnostic only. PHOTO_READY remains authoritative.
        }
    }

    private fun report(event: String, status: String) {
        BridgeJournal.record(this, "service", event)
        BridgeJournal.status(this, status)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, BridgeActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, W610BridgeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("CyanBridge W610 ChatGPT Test").setContentText(BridgeJournal.status(this))
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "Отключить BLE", stop).build()).build()
    }
    override fun onDestroy() {
        wanted = false
        ready = false
        if (registered) {
            sdk.removeOutDeviceListener(LISTENER)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
            if (hasNearby()) runCatching { manager.disconnect() }
        }
        scope.cancel()
        GlassesSessionCoordinator.clearForDisconnectedDevice()
        BridgeJournal.status(this, "Служба остановлена")
        BridgeJournal.record(this, "service", "SERVICE_STOPPED")
        super.onDestroy()
    }
    companion object {
        const val CONNECT = "com.fersaiyan.cyanbridge.w610chatgpt.CONNECT"
        const val CAPTURE = "com.fersaiyan.cyanbridge.w610chatgpt.CAPTURE"
        const val STOP = "com.fersaiyan.cyanbridge.w610chatgpt.STOP"
        val NEARBY = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        private const val CHANNEL = "w610_ble"
        private const val NOTIFICATION = 610
        private const val LISTENER = 611
        private var sdkInitialized = false
    }
}
