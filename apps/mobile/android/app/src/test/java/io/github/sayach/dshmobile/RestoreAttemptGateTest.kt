package io.github.sayach.dshmobile

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production callback gate without requiring an Android Activity or a real credential. */
class RestoreAttemptGateTest {
    @Test
    fun aSecondFailureDoesNotClaimAnotherRenewalWhileCookiesAreBeingInstalled() {
        val gate = RestoreAttemptGate()
        val generation = gate.begin()

        assertTrue(gate.claimRestore(generation))
        assertTrue(gate.restorePending)
        assertFalse(gate.claimRestore(generation))
        gate.cancelRetry()
        assertTrue(gate.restorePending)

        gate.completeRestore(generation)
        assertFalse(gate.restorePending)
        assertTrue(gate.claimRestore(generation))
    }

    @Test
    fun aLateCookieCompletionCannotReleaseANewerRestoreOwner() {
        val gate = RestoreAttemptGate()
        val previous = gate.begin()
        assertTrue(gate.claimRestore(previous))
        val current = gate.begin()
        assertTrue(gate.claimRestore(current))
        var opened = false

        gate.completeRestore(previous)
        gate.callback(previous) {
            opened = true
            gate.completeRestore(previous)
        }.run()

        assertTrue(gate.restorePending)
        assertFalse(opened)
        assertFalse(gate.claimRestore(previous))
        gate.completeRestore(current)
        assertFalse(gate.restorePending)
    }

    @Test
    fun invalidationReleasesLogicalOwnershipWithoutAcceptingItsLateCallback() {
        val gate = RestoreAttemptGate()
        val generation = gate.begin()
        assertTrue(gate.claimRestore(generation))
        var delivered = false
        val callback = gate.callback(generation) { delivered = true }

        gate.invalidate()
        callback.run()

        assertFalse(gate.restorePending)
        assertFalse(delivered)
    }

    @Test
    fun aCurrentResultAndItsRetryStillRunNormally() {
        val gate = RestoreAttemptGate()
        val generation = gate.begin()
        val delivered = AtomicInteger()
        gate.callback(generation) { delivered.incrementAndGet() }.run()
        val retry = gate.retryCallback { delivered.incrementAndGet() }
        assertTrue(gate.retryPending)
        retry.run()
        retry.run()
        assertFalse(gate.retryPending)
        assertEquals(2, delivered.get())
    }

    @Test
    fun configurationInvalidationRejectsHeldRenewalSuccessAndFailure() {
        for (outcome in listOf("success", "failure")) {
            val gate = RestoreAttemptGate()
            val generation = gate.begin()
            val delivered = AtomicInteger()
            val result = gate.callback(generation) { delivered.incrementAndGet() }
            deliverHeld(result) { gate.invalidate() }
            assertEquals("Late $outcome must not save credentials, navigate, or start another retry", 0, delivered.get())
            assertFalse(gate.isCurrent(generation))
        }
    }

    @Test
    fun aHeldCookieCompletionCannotOpenTheBrowserAfterConfigurationTakesOwnership() {
        val gate = RestoreAttemptGate()
        val generation = gate.begin()
        val opened = AtomicInteger()
        val cookieCompletion = Runnable {
            if (gate.isCurrent(generation)) opened.incrementAndGet()
        }
        deliverHeld(cookieCompletion) { gate.invalidate() }
        assertEquals(0, opened.get())
    }

    @Test
    fun anOldQueuedRetryCannotCreateAnotherAttemptAfterManualNavigation() {
        val gate = RestoreAttemptGate()
        gate.begin()
        val started = AtomicInteger()
        val retry = gate.retryCallback {
            gate.begin()
            started.incrementAndGet()
        }
        gate.invalidate()
        val configurationGeneration = gate.generation
        retry.run()
        assertEquals(0, started.get())
        assertEquals(configurationGeneration, gate.generation)
        assertFalse(gate.retryPending)
    }

    @Test
    fun aCancelledRetryCannotReleaseOrReplaceTheNextRetryEvenInTheSameGeneration() {
        val gate = RestoreAttemptGate()
        val generation = gate.begin()
        val oldCalls = AtomicInteger()
        val newCalls = AtomicInteger()
        val previous = gate.retryCallback { oldCalls.incrementAndGet() }
        gate.cancelRetry()
        val current = gate.retryCallback { newCalls.incrementAndGet() }
        previous.run()
        assertTrue(gate.retryPending)
        assertTrue(gate.isCurrent(generation))
        assertEquals(0, oldCalls.get())
        current.run()
        assertFalse(gate.retryPending)
        assertEquals(1, newCalls.get())
    }

    @Test
    fun aNewAttemptWithdrawsThePreviousRetryWithoutBlockingItsOwnRetry() {
        val gate = RestoreAttemptGate()
        gate.begin()
        val previous = gate.retryCallback { throw AssertionError("Old retry ran") }
        val generation = gate.begin()
        val calls = AtomicInteger()
        val current = gate.retryCallback { calls.incrementAndGet() }
        previous.run()
        assertTrue(gate.retryPending)
        assertTrue(gate.isCurrent(generation))
        current.run()
        assertEquals(1, calls.get())
    }

    @Test
    fun cancellingRetryDoesNotInvalidateTheRenewalThatIsInstallingCookies() {
        val gate = RestoreAttemptGate()
        val generation = gate.begin()
        val completed = AtomicInteger()
        gate.retryCallback { throw AssertionError("Cancelled retry ran") }
        gate.cancelRetry()
        gate.callback(generation) { completed.incrementAndGet() }.run()
        assertEquals(1, completed.get())
        assertTrue(gate.isCurrent(generation))
        assertFalse(gate.retryPending)
    }

    private fun deliverHeld(callback: Runnable, cancel: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val delivery = executor.submit {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "Held restore callback was not released" }
                callback.run()
            }
            assertTrue("Restore must be in flight before the configuration transition", entered.await(10, TimeUnit.SECONDS))
            cancel()
            release.countDown()
            delivery.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue("Owned callback executor must terminate", executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
