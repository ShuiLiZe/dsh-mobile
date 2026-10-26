package io.github.sayach.dshmobile

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

/** Controlled delayed renew/cookie and timeout interleavings, using the real restore/attempt gates. */
class SameHostSwitchLifecycleTest {
    @Test fun remoteProbeCompletesWhileLanRenewalIsHeldAndFencesLateSourceEpoch() {
        for (sourceChanges in listOf(false, true)) {
            val executor = Executors.newFixedThreadPool(3)
            val probeExecutor = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
                TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(1))
            val physical = NativeRestoreWorkGate<String>(executor)
            val restore = RestoreAttemptGate()
            val sourceEpoch = restore.begin()
            assertTrue(restore.claimRestore(sourceEpoch))
            val lifecycle = SameHostSwitchGate().apply { resume() }
            val enteredStatuses = CountDownLatch(2)
            val enteredLan = CountDownLatch(1)
            val releaseLan = CountDownLatch(1)
            val remoteFinished = CountDownLatch(1)
            val callback = AtomicReference<Runnable>()
            var prompts = 0
            try {
                repeat(2) {
                    executor.execute {
                        enteredStatuses.countDown()
                        check(releaseLan.await(5, TimeUnit.SECONDS))
                    }
                }
                assertTrue(enteredStatuses.await(5, TimeUnit.SECONDS))
                physical.submit("lan-instance:lan-token", { true }, { fail("LAN work rejected") }) {
                    enteredLan.countDown()
                    check(releaseLan.await(5, TimeUnit.SECONDS))
                }
                assertTrue(enteredLan.await(5, TimeUnit.SECONDS))
                val probeOwner = lifecycle.begin(0)!!
                physical.submit("remote-instance:remote-token", { lifecycle.current(probeOwner) }, { fail("Remote work rejected") }, workExecutor = probeExecutor) {
                    callback.set(Runnable {
                        if (lifecycle.current(probeOwner) && restore.isCurrent(sourceEpoch)) prompts++
                        lifecycle.finish(probeOwner)
                    })
                    remoteFinished.countDown()
                }
                assertTrue("Remote must finish before LAN exits", remoteFinished.await(5, TimeUnit.SECONDS))
                assertEquals(1L, releaseLan.count)
                assertTrue(restore.restorePending)
                if (sourceChanges) restore.invalidate()
                callback.get().run()
                assertEquals(if (sourceChanges) 0 else 1, prompts)
            } finally {
                releaseLan.countDown()
                executor.shutdown()
                probeExecutor.shutdown()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
                assertTrue(probeExecutor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun dedicatedProbeCapacityNeverBypassesTheSameCredentialPhysicalSlot() {
        val restoreExecutor = Executors.newSingleThreadExecutor()
        val probeExecutor = Executors.newSingleThreadExecutor()
        val physical = NativeRestoreWorkGate<String>(restoreExecutor)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val probeCompleted = CountDownLatch(1)
        try {
            physical.submit("instance:token", { true }, { fail("Rejected renewal") }) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            physical.submit("instance:token", { true }, { fail("Rejected probe") }, workExecutor = probeExecutor) {
                probeCompleted.countDown()
            }
            assertEquals(1L, probeCompleted.count)
            release.countDown()
            assertTrue(probeCompleted.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            restoreExecutor.shutdown()
            probeExecutor.shutdown()
            assertTrue(restoreExecutor.awaitTermination(5, TimeUnit.SECONDS))
            assertTrue(probeExecutor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun backgroundingAnAutomaticSwitchRejectsDelayedRenewAndCookieNavigation() {
        val deadlines = mutableListOf<Runnable>()
        val attempts = ConnectionAttemptController(
            schedule = { _, task -> deadlines += task; { } },
            cancellationFactory = { NativeConnectionCancellation { it.run() } },
        )
        val lifecycle = SameHostSwitchGate().apply { resume() }
        val restore = RestoreAttemptGate()
        val owner = lifecycle.generation
        val generation = restore.begin()
        val attempt = attempts.start(30_000) { fail("Withdrawn switch must not deliver terminal UI") }
        var saved = 0
        var navigated = 0
        val delayedRenew = restore.callback(generation) {
            if (attempts.isCurrent(attempt) && lifecycle.foreground && lifecycle.generation == owner) saved++
        }
        val delayedCookie = restore.callback(generation) { if (attempts.isCurrent(attempt)) navigated++ }

        lifecycle.pause()
        attempts.withdraw()
        restore.invalidate()
        delayedRenew.run()
        delayedCookie.run()
        deadlines.single().run()
        assertEquals(0, saved)
        assertEquals(0, navigated)
        assertTrue(attempt.cancellation.isCancelled)
        lifecycle.resume()
        delayedRenew.run()
        delayedCookie.run()
        assertEquals(0, saved)
        assertEquals(0, navigated)
    }

    @Test fun pendingCookiesKeepMemoryAndPersistedSourceSelectionUntilOwnedSuccess() {
        for (pauseBeforeCookies in listOf(true, false)) {
            val lifecycle = SameHostSwitchGate().apply { resume() }
            val restore = RestoreAttemptGate()
            val owner = lifecycle.generation
            val generation = restore.begin()
            var activeKey = "remote-source"
            var mode = AccessMode.REMOTE
            val preferences = mutableMapOf("lastKey" to activeKey, "origin" to "https://remote.example")
            // Successful authenticated renewal may persist the target row, but not activate it.
            var targetLearned = false
            restore.callback(generation) { targetLearned = true }.run()
            val cookies = restore.callback(generation) {
                if (lifecycle.foreground && lifecycle.generation == owner) {
                    activeKey = "lan-target"
                    mode = AccessMode.LAN
                    preferences["lastKey"] = activeKey
                    preferences["origin"] = "https://192.168.1.2:3443"
                }
            }
            assertTrue(targetLearned)
            assertEquals("remote-source", activeKey)
            assertEquals("remote-source", preferences["lastKey"])
            if (pauseBeforeCookies) { lifecycle.pause(); restore.invalidate() }
            cookies.run()
            if (pauseBeforeCookies) {
                assertEquals("remote-source", activeKey)
                assertEquals(AccessMode.REMOTE, mode)
                assertEquals("remote-source", preferences["lastKey"])
                assertEquals("https://remote.example", preferences["origin"])
                lifecycle.resume()
                cookies.run()
                assertEquals("remote-source", activeKey)
            } else {
                assertEquals("lan-target", activeKey)
                assertEquals(AccessMode.LAN, mode)
                assertEquals("lan-target", preferences["lastKey"])
                assertEquals("https://192.168.1.2:3443", preferences["origin"])
            }
        }
    }

    @Test fun failedLanTargetFinishesItsDeadlineWithoutStoppingHealthyRemote() {
        val deadlines = mutableListOf<Runnable>()
        val attempts = ConnectionAttemptController(
            schedule = { _, task -> deadlines += task; { } },
            cancellationFactory = { NativeConnectionCancellation { it.run() } },
        )
        var sourceStopped = false
        val target = attempts.start(30_000) { sourceStopped = true }
        // Activity failure handling must settle the real target owner before preserving source UI.
        assertTrue(attempts.finish(target))
        deadlines.single().run()
        assertFalse(attempts.isCurrent(target))
        assertFalse(sourceStopped)
        val retry = attempts.start(30_000) { sourceStopped = true }
        assertTrue(attempts.isCurrent(retry))
        assertFalse(sourceStopped)
    }
}
