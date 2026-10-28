package com.fersaiyan.cyanbridge.w610jpeg

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class OnceReleaseTest {
    @Test fun cancellationBeforeReceiverDispatchStillReleases() {
        val releases = AtomicInteger()
        val owner = OnceRelease { releases.incrementAndGet() }
        try { /* withContext never entered */ } finally { owner.close() }
        assertEquals(1, releases.get())
    }
    @Test fun receiverAndCallerFinallyReleaseOnlyOnce() {
        val releases = AtomicInteger()
        val owner = OnceRelease { releases.incrementAndGet() }
        try { owner.close() } finally { owner.close() }
        assertEquals(1, releases.get())
    }
    @Test fun racingCleanupCallsReleaseOnlyOnce() {
        val releases = AtomicInteger()
        val owner = OnceRelease { releases.incrementAndGet() }
        val gate = CountDownLatch(1)
        val threads = List(8) { Thread { gate.await(); owner.close() }.apply { start() } }
        gate.countDown()
        threads.forEach { it.join() }
        assertEquals(1, releases.get())
    }
}
