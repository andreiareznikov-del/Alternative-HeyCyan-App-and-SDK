package com.fersaiyan.cyanbridge.w610chatgpt

import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/** Positive evidence is an actual successful descriptor open by ChatGPT's UID. */
class BridgeFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Read only")
        val descriptor = super.openFile(uri, mode)
        val c = requireNotNull(context)
        val id = uri.lastPathSegment?.removeSuffix(".jpg")
        val targetUid = runCatching {
            @Suppress("DEPRECATION")
            c.packageManager.getApplicationInfo(ChatGptImageHandoff.TARGET, 0).uid
        }.getOrNull()
        if (descriptor != null && id != null && BridgeJournal.operationId.matches(id) && Binder.getCallingUid() == targetUid) {
            BridgeJournal.record(c, id, "CHATGPT_SHARE_ACCEPTED", "TARGET_OPENED_URI")
        }
        return descriptor
    }
}
