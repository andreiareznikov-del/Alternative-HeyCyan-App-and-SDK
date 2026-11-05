package com.fersaiyan.cyanbridge.w610chatgpt

import android.content.Context
import android.os.SystemClock
import java.io.File
import org.json.JSONObject

/** No image contents, URI, device address, question, caller identity or exception messages. */
internal object BridgeJournal {
    val operationId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private fun prefs(c: Context) = c.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    private fun file(c: Context) = File(c.filesDir, "w610-chatgpt.jsonl")
    @Synchronized fun events(c: Context, id: String): Set<String> =
        prefs(c).getStringSet("events.$id", emptySet())!!.toSet()

    @Synchronized fun record(c: Context, id: String, event: String, witness: String = "", value: Long? = null): Boolean {
        require(id == "service" || operationId.matches(id))
        require(Regex("[A-Z0-9_]{1,64}").matches(event))
        require(witness.isEmpty() || Regex("[A-Za-z0-9_]{1,80}").matches(witness))
        val prior = events(c, id)
        if (!EvidencePolicy.permits(event, prior, witness)) return false
        if (event in setOf("CHATGPT_SHARE_ACCEPTED", "IMAGE_ATTACHMENT_CONFIRMED", "LIVE_SESSION_CONTINUITY_VERIFIED") && event in prior) return false
        val data = JSONObject().put("elapsedMs", SystemClock.elapsedRealtime()).put("operation", id).put("event", event)
        if (witness.isNotEmpty()) data.put("evidence", witness)
        if (value != null) data.put("value", value)
        val log = file(c)
        val written = runCatching {
            if (log.length() > 1024 * 1024) log.writeText("")
            log.appendText(data.toString() + "\n")
        }.isSuccess
        if (!written) return false
        prefs(c).edit().putStringSet("events.$id", prior + event).apply()
        return true
    }

    fun latest(c: Context): String? = prefs(c).getString("latest", null)
    fun setLatest(c: Context, id: String) { prefs(c).edit().putString("latest", id).apply() }
    fun autoShare(c: Context) = prefs(c).getBoolean("autoShare", false)
    fun setAutoShare(c: Context, enabled: Boolean) { prefs(c).edit().putBoolean("autoShare", enabled).apply() }
    fun read(c: Context): String = if (file(c).exists()) file(c).readLines().takeLast(35).joinToString("\n") else ""
    fun export(c: Context) = file(c)
    fun staged(c: Context, id: String): File {
        require(operationId.matches(id))
        return File(File(c.filesDir, "shared").apply { mkdirs() }, "$id.jpg")
    }
    fun status(c: Context, value: String) { prefs(c).edit().putString("status", value).apply() }
    fun status(c: Context): String = prefs(c).getString("status", "Подключите очки")!!
}
