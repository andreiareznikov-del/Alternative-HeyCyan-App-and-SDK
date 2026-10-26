package com.fersaiyan.cyanbridge.ai.image

import android.graphics.BitmapFactory
import java.io.File
import java.io.RandomAccessFile

internal object ThumbnailJpegValidator {
    /** Bounds alone accept truncated JPEGs. Require framing AND an actual pixel decode. */
    fun isValid(file: File): Boolean = runCatching {
        if (file.length() !in 4L..8L * 1024 * 1024) return false
        RandomAccessFile(file, "r").use { input ->
            if (input.readUnsignedShort() != 0xFFD8) return false
            input.seek(input.length() - 2L)
            if (input.readUnsignedShort() != 0xFFD9) return false
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outMimeType != "image/jpeg" || bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) {
            return false
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        try {
            bitmap.width == bounds.outWidth && bitmap.height == bounds.outHeight
        } finally {
            bitmap.recycle()
        }
    }.getOrDefault(false)
}
