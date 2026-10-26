package io.github.sayach.dshmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bridge admission remains separate from the logical and physical native restore owners. */
class NativeSessionRenewalGateTest {
    @Test fun repeatedRequestsAreBusyUntilTheOriginalCookieCompletion() {
        val gate = NativeSessionRenewalGate()
        val replies = mutableListOf<String?>()
        val first = requireNotNull(gate.begin("first", replies::add))
        assertNull(gate.begin("second") { throw AssertionError("Busy request was admitted") })
        assertTrue(gate.pending)
        assertTrue(gate.finish(first))
        assertFalse(gate.finish(first))
        assertEquals(listOf<String?>(null), replies)
        assertFalse(gate.pending)
        assertTrue(gate.begin("third") {} != null)
    }

    @Test fun anOldPageCannotCancelOrCompleteTheNewDocumentsWaiter() {
        val gate = NativeSessionRenewalGate()
        val replies = mutableListOf<String?>()
        val first = requireNotNull(gate.begin("first", replies::add))
        assertTrue(gate.cancel("first"))
        val next = requireNotNull(gate.begin("next", replies::add))
        assertFalse(gate.cancel("first"))
        assertFalse(gate.finish(first))
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(next))
        assertTrue(gate.finish(next))
        assertEquals(listOf("cancelled", null), replies)
    }

    @Test fun overallDeadlineWithdrawsCookieCompletionAndRepliesOnceWithoutReleasingANewerOwner() {
        val gate = NativeSessionRenewalGate()
        val logical = RestoreAttemptGate()
        val replies = mutableListOf<String?>()
        val request = requireNotNull(gate.begin("first", replies::add))
        val generation = logical.begin()
        assertTrue(logical.claimRestore(generation))
        val deadlines = mutableListOf<Runnable>()
        val connections = ConnectionAttemptController(
            schedule = { _, callback -> deadlines.add(callback); {} },
            cancellationFactory = { NativeConnectionCancellation { it.run() } },
        )
        val attempt = connections.start(30_000) {
            gate.cancel(errorCode = "timeout")
            logical.invalidate()
        }
        deadlines.single().run()
        assertTrue(attempt.cancellation.isCancelled)
        assertFalse(connections.isCurrent(attempt))
        assertFalse(logical.isCurrent(generation))
        assertFalse(gate.isCurrent(request))
        assertFalse(gate.finish(request))
        assertEquals(listOf("timeout"), replies)
    }
}
