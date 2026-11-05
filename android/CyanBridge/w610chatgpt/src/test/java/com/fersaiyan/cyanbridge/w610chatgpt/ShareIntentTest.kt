package com.fersaiyan.cyanbridge.w610chatgpt

import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class ShareIntentTest {
    @Test fun usesExistingExplicitImageShareWithoutVoiceRelaunch() {
        val uri = Uri.parse("content://com.fersaiyan.cyanbridge.w610chatgpt.fileprovider/w610/sample.jpg")
        val intent = ChatGptImageHandoff.intent(uri)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("com.openai.chatgpt", intent.`package`)
        assertEquals("image/jpeg", intent.type)
        assertEquals(uri, intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertFalse(intent.hasExtra("conversation_id")) // Never invent an undocumented session contract.
    }
}
