package com.fersaiyan.cyanbridge.w610chatgpt

import org.junit.Assert.*
import org.junit.Test

class OperationGateTest {
    @Test fun hardwareButtonHasOneOwnerUntilFinish() {
        val gate = OperationGate()
        assertNotNull(gate.begin("one", "HARDWARE_BUTTON", 10))
        assertNull(gate.begin("duplicate", "HARDWARE_BUTTON", 11))
        assertEquals("one", gate.ready()?.id)
        assertNull(gate.ready())
        assertNull(gate.begin("readyTail", "READY_ONLY", 12))
    }
    @Test fun oldCompletionCannotClearNewOwner() {
        val gate = OperationGate()
        gate.begin("one", "APP_BUTTON", 10)
        gate.finish("one", 20)
        gate.begin("two", "HARDWARE_BUTTON", 4_000)
        gate.finish("one", 4_001)
        assertEquals("two", gate.active?.id)
    }
    @Test fun lateReadyAfterCompletionIsSuppressedButNextCaptureWorks() {
        val gate = OperationGate()
        gate.begin("one", "READY_ONLY", 10)
        gate.ready()
        gate.finish("one", 20)
        assertNull(gate.begin("tail", "READY_ONLY", 21))
        assertNotNull(gate.begin("two", "HARDWARE_BUTTON", 3_020))
    }
    @Test fun timeoutReleasesGate() {
        val gate = OperationGate()
        gate.begin("one", "APP_BUTTON", 10)
        gate.finish("one", 5_010)
        assertNull(gate.active)
        assertNotNull(gate.begin("two", "HARDWARE_BUTTON", 8_010))
    }
    @Test fun noReadyExistsWithoutOperation() { assertNull(OperationGate().ready()) }
}
