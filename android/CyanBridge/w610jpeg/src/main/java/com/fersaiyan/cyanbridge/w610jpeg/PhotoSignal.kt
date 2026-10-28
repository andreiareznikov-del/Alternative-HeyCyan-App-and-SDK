package com.fersaiyan.cyanbridge.w610jpeg

/** The same 0x02/0x03 framing as MainActivity, with bounds checked before indexing. */
internal enum class PhotoSignal {
    READY, CAMERA_BUTTON;

    companion object {
        fun fromNotify(data: ByteArray): PhotoSignal? = when {
            data.size < 7 -> null
            (data[6].toInt() and 0xFF) == 0x02 -> READY
            (data[6].toInt() and 0xFF) == 0x03 && data.getOrNull(7)?.toInt() == 1 -> CAMERA_BUTTON
            else -> null
        }
    }
}
