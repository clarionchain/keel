package io.clarionchain.keel.core

data class BalanceBreakdown(
    val spendable: Sats,
    val pendingRound: Sats,
    val lightningLocked: Sats,
    val boardPending: Sats,
    val exitPending: Sats,
    val onchain: Sats,
    /** Expired VTXOs: still ours, but only recoverable on-chain via exit. Never spendable. */
    val expired: Sats = Sats.ZERO,
    /** Spendable VTXOs close to expiry; should be refreshed in a round soon. */
    val expiringSoon: Sats = Sats.ZERO,
) {
    // Note: expiringSoon is a spendable subset (informational), so it is not added again.
    val total: Sats
        get() = spendable + pendingRound + lightningLocked + boardPending + exitPending + onchain + expired
}

data class FiatQuote(
    val currency: String,
    val satsPerUnitScaled: Long,
    val source: String,
    val asOfEpochMs: Long,
) {
    init {
        require(currency.matches(Regex("[A-Z]{3}")))
        require(source.isNotBlank())
    }
}
