package io.clarionchain.keel.core

/** Send-failure classification. Timeouts may still land on a later sync. */
object SendError {
    fun isUncertain(msg: String): Boolean =
        Regex("timed? ?out|deadline exceeded|unavailable|failed to fetch|networkerror|connection", RegexOption.IGNORE_CASE)
            .containsMatchIn(msg)

    fun friendly(msg: String): String = when {
        Regex("different server|invalid ark server", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
            "This address belongs to a different Ark server. Keel pays through ark.signet.2nd.dev — the destination wallet must too."
        Regex("different network|network mismatch", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
            "That address is for a different Bitcoin network."
        Regex("unknown delivery", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
            "Keel can't deliver to this Ark address."
        Regex("insufficient|don't cover amount|not enough", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
            "Not enough spendable sats for this send (including the fee)."
        Regex("invalid arkoor|failed signet validation|invalid ark address", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
            "This Ark address isn't payable from Keel."
        Regex("unknown payment", RegexOption.IGNORE_CASE).containsMatchIn(msg) ->
            "The Ark server lost track of an old payment record. Your funds are safe. If this stays, restore from your seed backup to resync."
        else -> msg
    }

    fun recentOutboundFound(
        history: List<OutboundMovement>,
        amountSats: Long,
        sinceEpochMs: Long,
    ): Boolean = history.any { m ->
        val kind = m.kind.lowercase()
        m.createdAtEpochMs >= sinceEpochMs &&
            (kind.contains("arkoor") || kind.contains("lightning") || kind.contains("offboard")) &&
            m.effectiveBalanceSats < 0 &&
            kotlin.math.abs(m.effectiveBalanceSats) >= amountSats
    }

    data class OutboundMovement(
        val kind: String,
        val effectiveBalanceSats: Long,
        val createdAtEpochMs: Long,
    )
}
