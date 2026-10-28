package com.fersaiyan.cyanbridge.w610jpeg

import java.util.concurrent.atomic.AtomicBoolean

/** Owns the gap before withContext dispatch as well as the receiver's finally block. */
internal class OnceRelease(private val release: () -> Unit) {
    private val open = AtomicBoolean(true)
    fun close() {
        if (open.compareAndSet(true, false)) release()
    }
}
