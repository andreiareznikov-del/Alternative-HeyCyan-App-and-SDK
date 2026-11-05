package com.fersaiyan.cyanbridge.w610chatgpt

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.fersaiyan.cyanbridge.ai.image.ThumbnailJpegValidator
import java.io.File

/** Adapted from CyanBridge MainActivity's existing external assistant handoff functions. */
internal object ChatGptImageHandoff {
    const val TARGET = "com.openai.chatgpt"
    fun intent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        setPackage(TARGET)
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, "Опиши, что видно на этом снимке с очков.")
        clipData = ClipData.newRawUri("CyanBridge image", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun onImageReadyForQuestion(c: Context, id: String, source: File): Boolean {
        if (!ThumbnailJpegValidator.isValid(source)) {
            BridgeJournal.record(c, id, "ERROR", "INVALID_JPEG_AT_HANDOFF")
            return false
        }
        source.copyTo(BridgeJournal.staged(c, id), overwrite = true)
        BridgeJournal.setLatest(c, id)
        return true
    }

    fun triggerAssistantImageQuery(c: Context, id: String, userInitiated: Boolean = false): Boolean =
        handOffImageToExternalAssistant(c, id, userInitiated)

    private fun handOffImageToExternalAssistant(c: Context, id: String, userInitiated: Boolean): Boolean {
        if (!userInitiated && "CHATGPT_SHARE_STARTED" in BridgeJournal.events(c, id)) return false
        val file = BridgeJournal.staged(c, id)
        if (!ThumbnailJpegValidator.isValid(file)) {
            BridgeJournal.record(c, id, "ERROR", "STAGED_JPEG_UNAVAILABLE")
            return false
        }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", file)
        if (intent(uri).resolveActivity(c.packageManager) == null) {
            BridgeJournal.record(c, id, "ERROR", "CHATGPT_SHARE_UNAVAILABLE")
            return false
        }
        return startExternalImageShare(c, id, uri, userInitiated)
    }

    private fun startExternalImageShare(c: Context, id: String, uri: Uri, userInitiated: Boolean): Boolean {
        val share = intent(uri).apply { if (c !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return try {
            c.grantUriPermission(TARGET, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Record BEFORE launching: the receiver can open the URI immediately on another thread.
            BridgeJournal.record(c, id, "CHATGPT_SHARE_STARTED", if (userInitiated) "USER_REQUEST" else "AUTO_EXPERIMENT")
            c.startActivity(share)
            BridgeJournal.record(c, id, "INTENT_DISPATCH_RETURNED")
            true // Dispatch only. Does NOT confirm receipt, attachment, prompt sent or Live.
        } catch (_: SecurityException) {
            BridgeJournal.record(c, id, "ERROR", "SHARE_SECURITY_REJECTED"); false
        } catch (_: android.content.ActivityNotFoundException) {
            BridgeJournal.record(c, id, "ERROR", "CHATGPT_ACTIVITY_UNAVAILABLE"); false
        } catch (_: RuntimeException) {
            BridgeJournal.record(c, id, "ERROR", "SHARE_DISPATCH_FAILED"); false
        }
    }
}
