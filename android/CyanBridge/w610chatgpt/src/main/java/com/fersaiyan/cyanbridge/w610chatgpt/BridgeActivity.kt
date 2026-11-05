package com.fersaiyan.cyanbridge.w610chatgpt

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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.widget.*
import androidx.core.content.ContextCompat

/** Only device selection and explicit observer evidence. Leaving this UI never disconnects BLE. */
@SuppressLint("MissingPermission")
class BridgeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var journal: TextView
    private var dialog: AlertDialog? = null
    private var rows: ArrayAdapter<String>? = null
    private val devices = linkedMapOf<String, BluetoothDevice>()
    private var scanning = false
    private val bluetooth get() = getSystemService(BluetoothManager::class.java).adapter
    private val refresh = object : Runnable {
        override fun run() {
            status.text = BridgeJournal.status(this@BridgeActivity)
            journal.text = BridgeJournal.read(this@BridgeActivity)
            handler.postDelayed(this, 1_000)
        }
    }
    private val stopScan = Runnable { stopScanning() }
    private val scanner = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread {
                if (!scanning || !hasNearby()) return@runOnUiThread
                devices[result.device.address] = result.device
                rows?.run {
                    clear()
                    addAll(devices.values.map { "${it.name ?: "Без имени"}\n${it.address}" })
                    notifyDataSetChanged()
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { stopScanning(); message("Поиск BLE не выполнен: $errorCode") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        column.addView(TextView(this).apply { text = "CyanBridge W610 → ChatGPT"; textSize = 23f })
        column.addView(TextView(this).apply {
            text = "Эксперимент 0.1. BLE работает в службе.\nShare может открыть другой экран или чат ChatGPT. Сохранение Live пока не подтверждено."
        })
        status = TextView(this).apply { textSize = 17f }
        column.addView(status)
        fun button(label: String, action: () -> Unit) {
            column.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Найти и подключить W610", ::findGlasses)
        button("Снять кнопкой приложения") {
            if (hasNearby()) ContextCompat.startForegroundService(this, Intent(this, W610BridgeService::class.java).setAction(W610BridgeService.CAPTURE))
            else message("Сначала подключите очки и разрешите Bluetooth")
        }
        column.addView(Switch(this).apply {
            text = "Автопередача в ChatGPT — эксперимент"
            isChecked = BridgeJournal.autoShare(this@BridgeActivity)
            setOnCheckedChangeListener { _, enabled -> BridgeJournal.setAutoShare(this@BridgeActivity, enabled) }
        })
        button("Передать последнее фото в ChatGPT") {
            val id = BridgeJournal.latest(this)
            if (id == null || !BridgeJournal.staged(this, id).exists()) message("Сначала получите новый JPEG")
            else if ("CHATGPT_SHARE_STARTED" in BridgeJournal.events(this, id)) {
                AlertDialog.Builder(this).setMessage("Передача уже запускалась. Повтор может создать дубликат или сменить чат.")
                    .setPositiveButton("Повторить вручную") { _, _ -> ChatGptImageHandoff.triggerAssistantImageQuery(this, id, true) }
                    .setNegativeButton("Отмена", null).show()
            } else ChatGptImageHandoff.triggerAssistantImageQuery(this, id, true)
        }
        button("Подтвердить: вложение видно в ChatGPT") {
            confirmEvidence("IMAGE_ATTACHMENT_CONFIRMED", "USER_OBSERVED_ATTACHMENT",
                "Вы лично видели этот снимок прикреплённым в ChatGPT? Открытие приложения само по себе не считается подтверждением.")
        }
        button("Подтвердить: сохранился тот же Live") {
            confirmEvidence("LIVE_SESSION_CONTINUITY_VERIFIED", "USER_VERIFIED_SAME_LIVE",
                "Вы проверили прежнюю контрольную фразу, тот же разговор и голосовой ответ именно по этому изображению? Это отметка наблюдателя, не автоматическая проверка.")
        }
        button("Сохранить журнал") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "text/plain"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, "w610-chatgpt-test.jsonl")
            }, EXPORT)
        }
        button("Остановить BLE") { stopService(Intent(this, W610BridgeService::class.java)) }
        journal = TextView(this).apply { textSize = 10f; setTextIsSelectable(true) }
        column.addView(journal)
        setContentView(ScrollView(this).apply { addView(column) })
        BridgeJournal.record(this, "service", "APP_OPENED", value = Build.VERSION.SDK_INT.toLong())
    }

    private fun confirmEvidence(event: String, witness: String, text: String) {
        val id = BridgeJournal.latest(this) ?: return message("Нет операции для подтверждения")
        AlertDialog.Builder(this).setMessage("Операция ${id.take(8)}. $text")
            .setPositiveButton("Да, проверено") { _, _ ->
                if (!BridgeJournal.record(this, id, event, witness)) message("Недостаточно предыдущих подтверждений или отметка уже записана")
            }.setNegativeButton("Отмена", null).show()
    }
    private fun hasNearby() = W610BridgeService.NEARBY.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun findGlasses() {
        if (!hasNearby()) {
            val permissions = W610BridgeService.NEARBY.toMutableList()
            if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(permissions.toTypedArray(), NEARBY_REQUEST)
            return
        }
        if (bluetooth == null) { message("Bluetooth недоступен"); return }
        if (!bluetooth.isEnabled) {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BT)
            return
        }
        try {
            stopScanning()
            devices.clear()
            rows = ArrayAdapter(this, android.R.layout.simple_list_item_1)
            dialog = AlertDialog.Builder(this).setTitle("Выберите свои W610")
                .setAdapter(rows) { _, index ->
                    devices.values.elementAtOrNull(index)?.let { device ->
                        stopScanning()
                        ContextCompat.startForegroundService(this, Intent(this, W610BridgeService::class.java)
                            .setAction(W610BridgeService.CONNECT).putExtra("device", device.address))
                    }
                }.setNegativeButton("Закрыть", null).create().apply {
                    setOnDismissListener { stopScanning() }; show()
                }
            scanning = true
            bluetooth.bluetoothLeScanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanner)
            handler.postDelayed(stopScan, 15_000)
        } catch (_: RuntimeException) { stopScanning(); message("Поиск BLE недоступен") }
    }
    private fun stopScanning() {
        handler.removeCallbacks(stopScan)
        if (scanning && hasNearby()) runCatching { bluetooth?.bluetoothLeScanner?.stopScan(scanner) }
        scanning = false
    }
    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onStop() { stopScanning(); super.onStop() } // Does not own/disconnect the service.
    override fun onDestroy() { dialog?.dismiss(); handler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == NEARBY_REQUEST && hasNearby()) findGlasses()
    }
    @Deprecated("Isolated test UI")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (result != RESULT_OK) return
        if (code == ENABLE_BT) findGlasses()
        if (code == EXPORT) data?.data?.let { uri ->
            runCatching { requireNotNull(contentResolver.openOutputStream(uri)).use { out -> BridgeJournal.export(this).inputStream().use { it.copyTo(out) } } }
                .onFailure { message("Не удалось сохранить журнал") }
        }
    }
    companion object {
        private const val NEARBY_REQUEST = 1
        private const val ENABLE_BT = 2
        private const val EXPORT = 3
    }
}
