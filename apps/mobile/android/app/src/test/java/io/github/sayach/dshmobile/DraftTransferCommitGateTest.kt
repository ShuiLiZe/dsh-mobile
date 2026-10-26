package io.github.sayach.dshmobile

import org.junit.Assert.*
import org.junit.Test

class DraftTransferCommitGateTest {
    @Test fun targetAloneCannotCommitBeforeSourceValidationAndReadback() {
        val gate = DraftTransferCommitGate()
        assertFalse(gate.sourceValidated())
        assertTrue(gate.targetRestored())
        assertEquals(DraftTransferCommitGate.Phase.VERIFY, gate.phase)
        assertFalse(gate.targetRestored())
        assertFalse(gate.sourceValidated())
        assertTrue(gate.targetVerified())
        assertTrue(gate.sourceValidated())
        assertTrue(gate.commit(true, true))
        assertFalse(gate.commit(true, true))
    }
    @Test fun cancellationOrTimeoutAtEveryPhaseRejectsLateResults() {
        for (progress in 0..3) {
            val gate = DraftTransferCommitGate()
            if (progress >= 1) gate.targetRestored()
            if (progress >= 2) gate.targetVerified()
            if (progress >= 3) gate.sourceValidated()
            gate.abort()
            assertFalse(gate.targetRestored())
            assertFalse(gate.targetVerified())
            assertFalse(gate.sourceValidated())
            assertFalse(gate.commit(true, true))
        }
    }
    @Test fun changedTargetOrWithdrawnCredentialOwnerRejectsFinalCommit() {
        for ((target, owner) in listOf(false to true, true to false, false to false)) {
            val gate = DraftTransferCommitGate()
            gate.targetRestored(); gate.targetVerified(); gate.sourceValidated()
            assertFalse(gate.commit(target, owner))
            assertEquals(DraftTransferCommitGate.Phase.ENDED, gate.phase)
            assertFalse(gate.commit(true, true))
        }
    }
    @Test fun prematureCommitIsTerminalInsteadOfResumingOldTransaction() {
        val gate = DraftTransferCommitGate()
        assertFalse(gate.commit(true, true))
        assertFalse(gate.targetRestored())
    }
}
