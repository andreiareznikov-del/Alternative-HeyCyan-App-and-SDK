package com.fersaiyan.cyanbridge.w610jpeg

import org.junit.Assert.*
import org.junit.Test

class PhotoSignalTest {
    @Test fun emptyAndShortNotificationsAreIgnored() {
        for (length in 0..6) assertNull(PhotoSignal.fromNotify(ByteArray(length)))
    }
    @Test fun readyRequiresNoEighthByte() {
        assertEquals(PhotoSignal.READY, PhotoSignal.fromNotify(byteArrayOf(0, 0, 0, 0, 0, 0, 2)))
    }
    @Test fun cameraButtonRequiresPressedByte() {
        assertEquals(PhotoSignal.CAMERA_BUTTON, PhotoSignal.fromNotify(byteArrayOf(0, 0, 0, 0, 0, 0, 3, 1)))
    }
    @Test fun shortCameraButtonIsIgnored() {
        assertNull(PhotoSignal.fromNotify(byteArrayOf(0, 0, 0, 0, 0, 0, 3)))
    }
    @Test fun buttonReleaseDoesNotCaptureAgain() {
        assertNull(PhotoSignal.fromNotify(byteArrayOf(0, 0, 0, 0, 0, 0, 3, 0)))
    }
    @Test fun batteryAndUnknownNotificationsDoNotTriggerCamera() {
        for (code in listOf(0, 5, 8, 9, 0xFF)) {
            assertNull(PhotoSignal.fromNotify(byteArrayOf(0, 0, 0, 0, 0, 0, code.toByte(), 1)))
        }
    }
}
