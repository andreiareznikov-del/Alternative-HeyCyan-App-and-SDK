package com.fersaiyan.cyanbridge.w610jpeg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyListener
import com.oudmon.ble.base.communication.bigData.resp.GlassesDeviceNotifyRsp
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Foreground-only harness. No CyanBridge Application, services, AI, network, or phone camera. */
@SuppressLint("MissingPermission") // Every SDK/adapter entry is gated by both Nearby permissions.
class JpegTestActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var journal: TextView
    private lateinit var preview: ImageView
    private lateinit var shutter: Button
    private var initialized = false
    private var ready = false
    private var closed = false
    private var foreground = false
    private var busy = false
    private var operation: Job? = null
    private var connectionTimeout: Job? = null
    private var scanTimeout: Job? = null
    private var readyWaiter: CompletableDeferred<Long>? = null
    private var picker: AlertDialog? = null
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private var pickerAdapter: ArrayAdapter<String>? = null
    private val bluetooth get() = getSystemService(BluetoothManager::class.java).adapter
    private val manager get() = BleOperateManager.getInstance()
    private val sdk get() = LargeDataHandler.getInstance()
    private val logFile get() = File(filesDir, "w610-jpeg.log")

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread {
                if (closed || picker?.isShowing != true || !hasNearby()) return@runOnUiThread
                val device = result.device
                val name = result.scanRecord?.deviceName ?: device.name ?: "Без имени"
                devices[device.address] = device
                val rows = devices.values.map { "${it.name ?: if (it.address == device.address) name else "Без имени"}\n${it.address}" }
                pickerAdapter?.run { clear(); addAll(rows); notifyDataSetChanged() }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { note("Ошибка поиска BLE: $errorCode"); stopScan() }
        }
    }

    private val connectionReceiver = object : QCBluetoothCallbackCloneReceiver() {
        override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
            if (!connected) runOnUiThread { if (!closed) connectionLost("BLE отключён") }
        }
        override fun onServiceDiscovered() {
            runOnUiThread {
                if (closed || !foreground || !hasNearby() || !manager.isConnected) return@runOnUiThread
                try {
                    sdk.initEnable() // Same vendor notification/MTU setup as MyBluetoothReceiver.
                    manager.isReady = true
                    connectionTimeout?.cancel()
                    ready = true
                    shutter.isEnabled = true
                    note("W610 подключены. Снимите предмет кнопкой очков или кнопкой ниже.")
                } catch (error: Exception) {
                    note("Не удалось включить уведомления BLE: ${error.message}")
                    disconnect()
                }
            }
        }
    }

    private val photoListener = object : GlassesDeviceNotifyListener() {
        override fun parseData(cmdType: Int, response: GlassesDeviceNotifyRsp) {
            val signal = PhotoSignal.fromNotify(response.loadData) ?: return
            val arrivedAt = SystemClock.elapsedRealtime()
            runOnUiThread {
                if (closed || !ready) return@runOnUiThread
                when (signal) {
                    PhotoSignal.READY -> {
                        note("photo-ready 0x02")
                        val waiter = readyWaiter
                        if (waiter != null) waiter.complete(arrivedAt)
                        else if (!busy) startPhoto(arrivedAt)
                    }
                    PhotoSignal.CAMERA_BUTTON -> startPhoto(null)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
                insets
            }
        }
        column.addView(TextView(this).apply { text = "W610 JPEG Test"; textSize = 24f })
        column.addView(TextView(this).apply {
            text = "Проверка фотографии. Оставьте этот экран открытым.\nДоступ нужен только к ближайшим устройствам."
        })
        status = TextView(this).apply { text = "Подключите W610"; textSize = 18f }
        column.addView(status)
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label; setOnClickListener { action() }; column.addView(this)
        }
        button("Найти и подключить W610", ::findGlasses)
        shutter = button("Сделать фото на очках") { startPhoto(null) }.apply { isEnabled = false }
        button("Отключить очки", ::disconnect)
        button("Сохранить журнал") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "text/plain"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, "w610-jpeg-test.log")
            }, EXPORT_LOG)
        }
        preview = ImageView(this).apply { adjustViewBounds = true; minimumHeight = pad * 8 }
        column.addView(preview)
        journal = TextView(this).apply { textSize = 11f; setTextIsSelectable(true) }
        column.addView(journal)
        setContentView(ScrollView(this).apply { addView(column) })
        note("JPEG-only build 1.0; Android ${android.os.Build.VERSION.SDK_INT}")
    }

    private fun hasNearby() = NEARBY.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun initializeSdk() {
        if (initialized) return
        check(hasNearby())
        if (!sdkInitialized) {
            BleOperateManager.getInstance(application).setApplication(application)
            BleBaseControl.getInstance(application).setmContext(application)
            manager.init()
            sdkInitialized = true
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(connectionReceiver, BleAction.getIntentFilter())
        initialized = true
        sdk.addOutDeviceListener(LISTENER_ID, photoListener)
    }

    private fun findGlasses() {
        if (busy) { note("Дождитесь завершения фотографии"); return }
        if (!hasNearby()) { requestPermissions(NEARBY, NEARBY_REQUEST); return }
        if (bluetooth == null) { note("Bluetooth недоступен"); return }
        if (!bluetooth.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BT)
            return
        }
        try {
            initializeSdk()
            disconnect()
            devices.clear()
            pickerAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1)
            picker = AlertDialog.Builder(this).setTitle("Выберите BLE-устройство W610")
                .setAdapter(pickerAdapter) { _, index ->
                    devices.values.elementAtOrNull(index)?.let { connect(it) }
                }.setNegativeButton("Закрыть") { _, _ -> stopScan() }
                .create().apply { setOnDismissListener { stopScan() }; show() }
            bluetooth.bluetoothLeScanner.startScan(null, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            scanTimeout = scope.launch { delay(15_000); stopScan() }
            note("Поиск BLE: выберите свои очки в списке")
        } catch (error: Exception) { stopScan(); note("Ошибка поиска: ${error.message}") }
    }

    private fun stopScan() {
        scanTimeout?.cancel()
        if (hasNearby()) runCatching { bluetooth?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun connect(device: BluetoothDevice) {
        stopScan()
        scope.launch {
            operation?.cancelAndJoin() // Old callback cleanup must finish before the new link.
            if (!foreground || !hasNearby()) return@launch
            try {
                DeviceManager.getInstance().deviceName = device.name ?: "W610"
                DeviceManager.getInstance().deviceAddress = device.address
                manager.connectDirectly(device.address)
                note("Подключение к ${device.name ?: "W610"}…")
                connectionTimeout = scope.launch {
                    delay(25_000)
                    if (!ready) { note("Нет ответа BLE. Проверьте выбор очков и остановите другие приложения очков."); disconnect() }
                }
            } catch (error: Exception) { note("Ошибка подключения: ${error.message}") }
        }
    }

    private fun startPhoto(photoReadyAt: Long?) {
        if (busy || !ready || !hasNearby() || !manager.isConnected) return
        val permit = GlassesSessionCoordinator.tryAcquireBackgroundCommand() ?: return
        if (!GlassesSessionCoordinator.canRequestThumbnail(permit)) {
            GlassesSessionCoordinator.releaseBackgroundCommand(permit)
            note("После незавершённой передачи отключите и снова подключите очки")
            return
        }
        busy = true
        shutter.isEnabled = false
        val ownership = OnceRelease { GlassesSessionCoordinator.releaseBackgroundCommand(permit) }
        val waiter = if (photoReadyAt == null) CompletableDeferred<Long>() else null
        readyWaiter = waiter
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var receiverOwnsPermit = false
            try {
                val confirmedAt = photoReadyAt ?: run {
                    note("Запрос свежего фото: качество Clearer")
                    withContext(Dispatchers.IO) {
                        cameraCommand(byteArrayOf(0x02, 0x01, 0x06, 4, 4))
                        delay(250)
                        cameraCommand(byteArrayOf(0x02, 0x01, 0x01))
                    }
                    withTimeout(5_000) { requireNotNull(waiter).await() }
                }
                readyWaiter = null
                receiverOwnsPermit = true
                val file = withContext(Dispatchers.IO) {
                    BleThumbnailTransfer(
                        transport = HeyCyanThumbnailTransport(),
                        connectionReady = { manager.isConnected && GlassesSessionCoordinator.canRequestThumbnail(permit) },
                        requireReconnect = { GlassesSessionCoordinator.requireThumbnailReconnect(permit) },
                        validateJpeg = ThumbnailJpegValidator::isValid,
                        clockMs = SystemClock::elapsedRealtime,
                        log = ::note,
                    ).receive(filesDir, "W610_${UUID.randomUUID()}", confirmedAt) {
                        ownership.close()
                    }
                }
                if (file == null) {
                    note("JPEG не получен. При таймауте переподключите очки.")
                } else {
                    val gallery = withContext(Dispatchers.IO) {
                        runCatching { W610PreviewStore.save(this@JpegTestActivity, file) }
                            .onFailure { note("Gallery error: ${it.message}") }.getOrNull()
                    }
                    val bitmap = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 2 })
                    }
                    preview.setImageBitmap(bitmap)
                    note("W610_PREVIEW_OK decoded=true bytes=${file.length()} galleryUri=$gallery")
                    note(if (gallery != null) "W610 JPEG OK — Галерея / CyanBridge W610" else "JPEG OK — копия в приложении; сохранение в галерею не удалось")
                }
            } catch (cancelled: CancellationException) {
                if (!receiverOwnsPermit) GlassesSessionCoordinator.requireThumbnailReconnect(permit)
                note("Операция остановлена: ${cancelled.javaClass.simpleName}. Переподключите очки.")
                throw cancelled
            } catch (error: Exception) {
                if (!receiverOwnsPermit) GlassesSessionCoordinator.requireThumbnailReconnect(permit)
                note("Фото: ${error.javaClass.simpleName}: ${error.message}")
            } finally {
                readyWaiter = null
                ownership.close()
                busy = false
                shutter.isEnabled = ready
            }
        }
    }

    private suspend fun cameraCommand(bytes: ByteArray) {
        val response = CompletableDeferred<GlassModelControlResponse>()
        sdk.glassesControl(bytes) { _, result -> response.complete(result) }
        val result = withTimeout(2_000) { response.await() }
        check(result.errorCode == 0) { "Camera error=${result.errorCode}" }
    }

    private fun connectionLost(message: String) {
        ready = false
        shutter.isEnabled = false
        connectionTimeout?.cancel()
        operation?.cancel()
        GlassesSessionCoordinator.clearForDisconnectedDevice()
        note(message)
    }

    private fun disconnect() {
        stopScan()
        if (initialized && hasNearby()) {
            runCatching {
                manager.setNeedConnect(false)
                BleBaseControl.getInstance().setNeedReconnect(false)
                manager.disconnect()
            }
        }
        if (::shutter.isInitialized) connectionLost("BLE отключён")
    }

    private fun note(message: String) {
        Log.i("W610JpegTest", message)
        synchronized(this) {
            runCatching {
                if (logFile.length() > 1024 * 1024) logFile.writeText("Older journal truncated\n")
                logFile.appendText("${SystemClock.elapsedRealtime()} $message\n")
            }
        }
        runOnUiThread {
            if (!closed && ::journal.isInitialized) {
                journal.text = (journal.text.toString().lineSequence().takeLastLines(45) + message).joinToString("\n")
                status.text = message
            }
        }
    }

    private fun Sequence<String>.takeLastLines(count: Int): List<String> = toList().takeLast(count)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NEARBY_REQUEST) {
            if (hasNearby()) findGlasses() else note("Для подключения разрешите доступ к ближайшим устройствам")
        }
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onStop() {
        foreground = false
        if (initialized) disconnect()
        super.onStop()
    }

    @Deprecated("Legacy activity result API is sufficient for this isolated harness")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == ENABLE_BT) findGlasses()
        if (requestCode == EXPORT_LOG) data?.data?.let { uri ->
            runCatching {
                requireNotNull(contentResolver.openOutputStream(uri)).use { output -> logFile.inputStream().use { it.copyTo(output) } }
            }.onSuccess { note("Журнал сохранён") }.onFailure { note("Не удалось сохранить журнал: ${it.message}") }
        }
    }

    override fun onDestroy() {
        closed = true
        picker?.dismiss()
        disconnect()
        if (initialized) {
            sdk.removeOutDeviceListener(LISTENER_ID)
            LocalBroadcastManager.getInstance(this).unregisterReceiver(connectionReceiver)
        }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private val NEARBY = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        private const val NEARBY_REQUEST = 1
        private const val ENABLE_BT = 2
        private const val EXPORT_LOG = 3
        private const val LISTENER_ID = 610
        // SDK init registers singleton packet parsers. Re-registering after Activity
        // recreation duplicates packets and corrupts thumbnails, so initialize once/process.
        private var sdkInitialized = false
    }
}
