package io.clarionchain.keel.core

/**
 * VTXO expiry classification. Pure Kotlin, no UniFFI types, so the send/balance
 * decision logic is unit-testable on the JVM.
 *
 * Ark rule: once the chain tip reaches a VTXO's expiry height the server refuses
 * to cosign spends of it. Expired funds are NOT spendable; they are recovered
 * on-chain via the unilateral exit path. VTXOs close to expiry should be
 * refreshed (new round) before they cross the threshold.
 */
enum class VtxoExpiryStatus {
    OK,
    EXPIRING_SOON,
    EXPIRED,
}

data class VtxoExpiryInput(
    val id: String,
    val amountSats: Long,
    val expiryHeight: Long,
)

data class VtxoExpiryReport(
    val tipHeight: Long,
    val ok: List<VtxoExpiryInput>,
    val expiringSoon: List<VtxoExpiryInput>,
    val expired: List<VtxoExpiryInput>,
) {
    val okSats: Long get() = ok.sumOf { it.amountSats }
    val expiringSoonSats: Long get() = expiringSoon.sumOf { it.amountSats }
    val expiredSats: Long get() = expired.sumOf { it.amountSats }
    val needsRefresh: Boolean get() = expiringSoon.isNotEmpty()
    val needsRecovery: Boolean get() = expired.isNotEmpty()
}

object VtxoExpiry {
    /** ~1 day of blocks; how close to expiry we start pushing a refresh. */
    const val DEFAULT_SOON_THRESHOLD_BLOCKS: Long = 144L

    fun status(expiryHeight: Long, tipHeight: Long, soonThreshold: Long): VtxoExpiryStatus {
        require(soonThreshold >= 0) { "threshold must be non-negative" }
        if (tipHeight >= expiryHeight) return VtxoExpiryStatus.EXPIRED
        if (expiryHeight - tipHeight <= soonThreshold) return VtxoExpiryStatus.EXPIRING_SOON
        return VtxoExpiryStatus.OK
    }

    fun classify(
        vtxos: List<VtxoExpiryInput>,
        tipHeight: Long,
        soonThreshold: Long = DEFAULT_SOON_THRESHOLD_BLOCKS,
    ): VtxoExpiryReport {
        val ok = mutableListOf<VtxoExpiryInput>()
        val soon = mutableListOf<VtxoExpiryInput>()
        val expired = mutableListOf<VtxoExpiryInput>()
        for (vtxo in vtxos) {
            when (status(vtxo.expiryHeight, tipHeight, soonThreshold)) {
                VtxoExpiryStatus.OK -> ok += vtxo
                VtxoExpiryStatus.EXPIRING_SOON -> soon += vtxo
                VtxoExpiryStatus.EXPIRED -> expired += vtxo
            }
        }
        return VtxoExpiryReport(tipHeight, ok, soon, expired)
    }

    /**
     * Defensive fallback ONLY. The Bark UniFFI binding surfaces server rejections as
     * plain message strings (sealed Exception.Inner), so a definitive expired-VTXO
     * rejection from the server is recognized by its wording and routed to the
     * recovery flow. Primary detection is always metadata-based (classify).
     *
     * Deliberately requires "vtxo" or "height" next to "expired" so that unrelated
     * expiry messages (e.g. a BOLT11 invoice expiring) do not match.
     */
    fun isExpiredVtxoRejection(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return "expired" in m && ("vtxo" in m || "height" in m)
    }
}

/**
 * Honest balance: expired VTXOs are still the user's funds, but they are not
 * normally spendable, so they move out of `spendable` into `expired`. Floors at
 * zero in case the SDK ever excludes expired VTXOs from its spendable total.
 */
fun BalanceBreakdown.adjustedForExpiry(report: VtxoExpiryReport): BalanceBreakdown {
    val expiredSats = report.expiredSats
    val healthy = if (spendable.value >= expiredSats) Sats(spendable.value - expiredSats) else Sats.ZERO
    return copy(
        spendable = healthy,
        expired = Sats(expiredSats),
        expiringSoon = Sats(report.expiringSoonSats),
    )
}
