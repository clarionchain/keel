package io.clarionchain.keel.core

object PaymentRequestParser {
    fun parse(raw: String, activeNetwork: BitcoinNetwork): ParsedPaymentRequest {
        val input = raw.trim()
        if (input.isEmpty()) {
            return ParsedPaymentRequest(PaymentKind.EMPTY, input)
        }
        val lower = input.lowercase()

        if (lower.startsWith("lnbcrt")) {
            return rejectIfWrongNetwork(
                input,
                detected = BitcoinNetwork.REGTEST,
                active = activeNetwork,
                kind = PaymentKind.LIGHTNING_INVOICE,
                reason = "regtest_invoice_on_other_network",
            ).copy(amountSats = bolt11AmountSats(lower.substringBeforeLast("1")))
        }
        if (lower.startsWith("lnbc")) {
            return rejectIfWrongNetwork(
                input,
                detected = BitcoinNetwork.MAINNET,
                active = activeNetwork,
                kind = PaymentKind.LIGHTNING_INVOICE,
                reason = "mainnet_on_signet",
            ).copy(amountSats = bolt11AmountSats(lower.substringBeforeLast("1")))
        }
        if (lower.startsWith("lntbs")) {
            return rejectIfWrongNetwork(
                input,
                detected = BitcoinNetwork.SIGNET,
                active = activeNetwork,
                kind = PaymentKind.LIGHTNING_INVOICE,
                reason = "signet_invoice_on_other_network",
            ).copy(amountSats = bolt11AmountSats(lower.substringBeforeLast("1")))
        }
        if (lower.startsWith("bitcoin:")) {
            val inner = input.substringAfter(":")
            val parsed = parse(inner.substringBefore("?"), activeNetwork)
            val kind = if (parsed.kind == PaymentKind.UNSUPPORTED) PaymentKind.UNSUPPORTED else PaymentKind.BIP21
            return parsed.copy(kind = kind, original = input)
        }
        if (looksLikeLightningAddress(input)) {
            return ParsedPaymentRequest(PaymentKind.LIGHTNING_ADDRESS, input, activeNetwork)
        }
        if (lower.startsWith("bc1") || looksLikeMainnetLegacy(input)) {
            return rejectIfWrongNetwork(
                input,
                detected = BitcoinNetwork.MAINNET,
                active = activeNetwork,
                kind = PaymentKind.ONCHAIN_ADDRESS,
                reason = "mainnet_on_signet",
            )
        }
        if (lower.startsWith("tb1")) {
            return rejectIfWrongNetwork(
                input,
                detected = BitcoinNetwork.SIGNET,
                active = activeNetwork,
                kind = PaymentKind.ONCHAIN_ADDRESS,
                reason = "signet_on_mainnet",
            )
        }
        if (lower.startsWith("bcrt1")) {
            return rejectIfWrongNetwork(
                input,
                detected = BitcoinNetwork.REGTEST,
                active = activeNetwork,
                kind = PaymentKind.ONCHAIN_ADDRESS,
                reason = "regtest_address_on_other_network",
            )
        }
        if (lower.startsWith("ark1") || lower.startsWith("tark") || lower.startsWith("ark")) {
            return ParsedPaymentRequest(PaymentKind.ARK_ADDRESS, input, activeNetwork)
        }
        return ParsedPaymentRequest(PaymentKind.UNSUPPORTED, input, reason = "unrecognized")
    }

    private fun rejectIfWrongNetwork(
        input: String,
        detected: BitcoinNetwork,
        active: BitcoinNetwork,
        kind: PaymentKind,
        reason: String,
    ): ParsedPaymentRequest {
        if (detected != active) {
            return ParsedPaymentRequest(PaymentKind.UNSUPPORTED, input, detected, reason = reason)
        }
        return ParsedPaymentRequest(kind, input, detected)
    }

    private fun looksLikeLightningAddress(input: String): Boolean {
        val at = input.indexOf('@')
        return at > 0 && at < input.length - 1 && !input.contains(' ') && input.contains('.')
    }

    private fun looksLikeMainnetLegacy(input: String): Boolean {
        return (input.startsWith("1") || input.startsWith("3")) && input.length in 26..35
    }

    /**
     * Amount encoded in a BOLT11 human-readable part (e.g. "lntbs100n").
     * Multipliers: m = 1e-3 BTC, u = 1e-6, n = 1e-9, p = 1e-12. Null when amountless.
     */
    internal fun bolt11AmountSats(hrp: String): Long? {
        val match = Regex("([0-9]+)([munp])?$").find(hrp) ?: return null
        val value = match.groupValues[1].toLongOrNull() ?: return null
        return when (match.groupValues[2]) {
            "" -> value * 100_000_000L
            "m" -> value * 100_000L
            "u" -> value * 100L
            "n" -> value / 10L
            "p" -> value / 1_000L
            else -> null
        }
    }
}
