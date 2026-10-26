package io.github.sayach.dshmobile

/** Serial callback ownership; no late result can reopen a cancelled transaction. */
internal class DraftTransferCommitGate {
    enum class Phase { TARGET, VERIFY, SOURCE, READY, ENDED }
    var phase = Phase.TARGET
        private set
    fun targetRestored(): Boolean = advance(Phase.TARGET, Phase.VERIFY)
    fun targetVerified(): Boolean = advance(Phase.VERIFY, Phase.SOURCE)
    fun sourceValidated(): Boolean = advance(Phase.SOURCE, Phase.READY)
    fun commit(targetIntact: Boolean, ownerCurrent: Boolean): Boolean {
        val accepted = phase == Phase.READY && targetIntact && ownerCurrent
        phase = Phase.ENDED
        return accepted
    }
    fun abort() { phase = Phase.ENDED }
    private fun advance(expected: Phase, next: Phase): Boolean {
        if (phase != expected) return false
        phase = next
        return true
    }
}
