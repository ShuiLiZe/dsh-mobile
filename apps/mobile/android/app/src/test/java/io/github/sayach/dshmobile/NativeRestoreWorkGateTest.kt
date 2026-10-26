package io.github.sayach.dshmobile

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Physical worker barriers complement the UI epoch and Cookie-callback owner checks. */
class NativeRestoreWorkGateTest {
    @Test
    fun duplicateFailuresKeepOneRenewalAndItsPendingCookieCompletion() = withWorkers { executor ->
        val physical = NativeRestoreWorkGate<String>(executor)
        val logical = RestoreAttemptGate()
        val generation = logical.begin()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val calls = AtomicInteger()
        val callbacks = ArrayDeque<Runnable>()
        try {
            fun submitIfNotRecovering() {
                if (logical.restorePending) return
                assertTrue(logical.claimRestore(generation))
                physical.submit("device", { logical.isCurrent(generation) }, { throw AssertionError("Rejected") }) {
                    calls.incrementAndGet()
                    entered.countDown()
                    await(release)
                    synchronized(callbacks) {
                        callbacks.addLast(logical.callback(generation) { logical.completeRestore(generation) })
                    }
                    exited.countDown()
                }
            }
            submitIfNotRecovering()
            await(entered)
            submitIfNotRecovering()
            assertEquals(1, calls.get())
            release.countDown()
            await(exited)
            // Finishing the network worker does not release the asynchronous Cookie owner.
            submitIfNotRecovering()
            assertTrue(logical.restorePending)
            assertEquals(1, calls.get())
            synchronized(callbacks) { callbacks.removeFirst() }.run()
            assertFalse(logical.restorePending)
        } finally { release.countDown() }
    }

    @Test
    fun cancelledRenewalRetainsItsSlotUntilExitAndFencesLateSuccessAndFailure() {
        for (outcome in listOf("success", "failure")) withWorkers { executor ->
            val scheduled = AtomicInteger()
            val physical = NativeRestoreWorkGate<String>(Executor {
                scheduled.incrementAndGet()
                executor.execute(it)
            })
            val logical = RestoreAttemptGate()
            val connections = connectionController()
            val first = connections.start(30_000) { }
            val firstGeneration = logical.begin()
            assertTrue(logical.claimRestore(firstGeneration))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val nextCompleted = CountDownLatch(1)
            val active = AtomicInteger()
            val maximum = AtomicInteger()
            val staleSuccess = AtomicInteger()
            val staleFailure = AtomicInteger()
            val latestUi = AtomicInteger()
            try {
                physical.submit("device", { connections.isCurrent(first) }, { throw AssertionError("Rejected") }) {
                    maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    entered.countDown()
                    await(release)
                    val result = runCatching {
                        if (outcome == "failure") throw IllegalStateException("Owned late network failure")
                        "Owned late Session"
                    }
                    logical.callback(firstGeneration) {
                        if (result.isSuccess) staleSuccess.incrementAndGet() else staleFailure.incrementAndGet()
                    }.run()
                    active.decrementAndGet()
                }
                await(entered)
                connections.cancel(first)
                logical.invalidate()
                assertTrue(first.cancellation.isCancelled)
                val next = connections.start(30_000) { }
                val nextGeneration = logical.begin()
                assertTrue(logical.claimRestore(nextGeneration))
                physical.submit("device", { connections.isCurrent(next) && logical.isCurrent(nextGeneration) }, { throw AssertionError("Rejected") }) {
                    maximum.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    logical.callback(nextGeneration) {
                        latestUi.incrementAndGet()
                        logical.completeRestore(nextGeneration)
                    }.run()
                    active.decrementAndGet()
                    nextCompleted.countDown()
                }
                assertEquals("A cancelled $outcome worker still owns the slot", 1L, nextCompleted.count)
                assertEquals("A successor must not enter the executor before physical exit", 1, scheduled.get())
                release.countDown()
                await(nextCompleted)
                assertEquals(1, maximum.get())
                assertEquals(0, staleSuccess.get())
                assertEquals(0, staleFailure.get())
                assertEquals(1, latestUi.get())
                assertEquals(2, scheduled.get())
                assertFalse(logical.restorePending)
                assertTrue(connections.isCurrent(next))
            } finally { release.countDown() }
        }
    }

    @Test
    fun onlyTheLatestWaitingOwnerRunsAfterTheOldWorkerActuallyExits() = withWorkers { executor ->
        val physical = NativeRestoreWorkGate<String>(executor)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val latestCompleted = CountDownLatch(1)
        val stale = AtomicInteger()
        try {
            physical.submit("device", { true }, { throw AssertionError("Rejected") }) {
                entered.countDown(); await(release)
            }
            await(entered)
            physical.submit("device", { true }, { throw AssertionError("Rejected") }) { stale.incrementAndGet() }
            physical.submit("device", { true }, { throw AssertionError("Rejected") }) { latestCompleted.countDown() }
            release.countDown()
            await(latestCompleted)
            assertEquals(0, stale.get())
        } finally { release.countDown() }
    }

    @Test
    fun aQueuedRestoresOverallDeadlineCanCancelImmediatelyWithoutReleasingTheOldWorker() = withWorkers { executor ->
        val deadlines = mutableListOf<Runnable>()
        val connections = ConnectionAttemptController(
            schedule = { _, task -> deadlines.add(task); { } },
            cancellationFactory = { NativeConnectionCancellation { task -> task.run() } },
        )
        val physical = NativeRestoreWorkGate<String>(executor)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val expiredCalls = AtomicInteger()
        var timeouts = 0
        val previous = connections.start(30_000) { }
        try {
            physical.submit("device", { connections.isCurrent(previous) }, { throw AssertionError("Rejected") }) {
                entered.countDown(); await(release)
            }
            await(entered)
            val expiring = connections.start(30_000) { reason ->
                assertEquals(ConnectionAttemptController.Reason.TIMED_OUT, reason)
                timeouts++
            }
            physical.submit("device", { connections.isCurrent(expiring) }, { throw AssertionError("Rejected") }) { expiredCalls.incrementAndGet() }
            deadlines.last().run()
            assertEquals(1, timeouts)
            assertTrue(expiring.cancellation.isCancelled)
            assertEquals(1L, release.count)
            val latest = connections.start(30_000) { throw AssertionError("Latest request ended") }
            physical.submit("device", { connections.isCurrent(latest) }, { throw AssertionError("Rejected") }) { completed.countDown() }
            release.countDown()
            await(completed)
            assertEquals(0, expiredCalls.get())
            assertTrue(connections.isCurrent(latest))
        } finally { release.countDown() }
    }

    @Test
    fun switchingToAnotherDeviceDoesNotWaitForThePreviousCredential() = withWorkers { executor ->
        val physical = NativeRestoreWorkGate<String>(executor)
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val otherCompleted = CountDownLatch(1)
        try {
            physical.submit("first-device", { true }, { throw AssertionError("Rejected") }) {
                firstEntered.countDown(); await(release)
            }
            await(firstEntered)
            physical.submit("second-device", { true }, { throw AssertionError("Rejected") }) { otherCompleted.countDown() }
            await(otherCompleted)
            assertEquals(1L, release.count)
        } finally { release.countDown() }
    }

    @Test
    fun aQueuedRequestIsRecheckedAfterCancellationWithoutBlockingItsSuccessor() {
        val tasks = ArrayDeque<Runnable>()
        val physical = NativeRestoreWorkGate<String>(Executor { tasks.addLast(it) })
        val current = AtomicBoolean(true)
        var stale = 0
        var latest = 0
        physical.submit("device", current::get, { throw AssertionError("Rejected") }) { stale++ }
        current.set(false)
        physical.submit("device", { true }, { throw AssertionError("Rejected") }) { latest++ }

        tasks.removeFirst().run()
        tasks.removeFirst().run()

        assertEquals(0, stale)
        assertEquals(1, latest)
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun executorRejectionReleasesBothPhysicalAndLogicalOwnershipForRetry() = withWorkers { executor ->
        val rejectNext = AtomicBoolean(true)
        val physical = NativeRestoreWorkGate<String>(Executor {
            if (rejectNext.getAndSet(false)) throw RejectedExecutionException("Owned fixture rejection")
            executor.execute(it)
        })
        val logical = RestoreAttemptGate()
        val firstGeneration = logical.begin()
        assertTrue(logical.claimRestore(firstGeneration))
        var rejected = 0
        physical.submit("device", { true }, {
            rejected++
            logical.completeRestore(firstGeneration)
        }) { throw AssertionError("Rejected operation ran") }
        assertEquals(1, rejected)
        assertFalse(logical.restorePending)
        val nextGeneration = logical.begin()
        assertTrue(logical.claimRestore(nextGeneration))
        val completed = CountDownLatch(1)
        physical.submit("device", { logical.isCurrent(nextGeneration) }, { throw AssertionError("Rejected again") }) {
            logical.completeRestore(nextGeneration)
            completed.countDown()
        }
        await(completed)
        assertFalse(logical.restorePending)
    }

    @Test
    fun aWorkerFailureReleasesItsSlotWithoutLosingAnAlreadyWaitingOwner() {
        val tasks = ArrayDeque<Runnable>()
        val physical = NativeRestoreWorkGate<String>(Executor { tasks.addLast(it) })
        var completed = 0
        physical.submit("device", { true }, { throw AssertionError("Rejected") }) {
            throw IllegalStateException("Owned worker failure")
        }
        physical.submit("device", { true }, { throw AssertionError("Rejected") }) { completed++ }
        try {
            tasks.removeFirst().run()
            throw AssertionError("Worker failure disappeared")
        } catch (failure: IllegalStateException) {
            assertEquals("Owned worker failure", failure.message)
        }
        tasks.removeFirst().run()
        assertEquals(1, completed)
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun aDirectExecutorsOperationFailureIsNotReportedAsDispatchRejection() {
        val physical = NativeRestoreWorkGate<String>(Executor(Runnable::run))
        var rejected = 0
        var next = 0
        try {
            physical.submit("device", { true }, { rejected++ }) {
                throw RejectedExecutionException("Owned operation failure")
            }
            throw AssertionError("Operation failure disappeared")
        } catch (failure: RejectedExecutionException) {
            assertEquals("Owned operation failure", failure.message)
        }
        physical.submit("device", { true }, { rejected++ }) { next++ }
        assertEquals(0, rejected)
        assertEquals(1, next)
    }

    private fun connectionController() = ConnectionAttemptController(
        schedule = { _, _ -> { } },
        cancellationFactory = { NativeConnectionCancellation { task -> task.run() } },
    )

    private fun await(latch: CountDownLatch) {
        assertTrue("Owned worker barrier did not settle", latch.await(5, TimeUnit.SECONDS))
    }

    private fun withWorkers(operation: (java.util.concurrent.ExecutorService) -> Unit) {
        val workers = Executors.newFixedThreadPool(3)
        try { operation(workers) } finally {
            workers.shutdown()
            assertTrue("Owned worker executor did not exit", workers.awaitTermination(10, TimeUnit.SECONDS))
        }
    }
}
