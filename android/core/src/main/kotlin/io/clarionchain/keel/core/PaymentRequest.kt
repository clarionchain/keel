package io.clarionchain.keel.core

enum class BitcoinNetwork {
    SIGNET,
    MAINNET,
    REGTEST,
}

enum class PaymentKind {
    EMPTY,
    ARK_ADDRESS,
    LIGHTNING_INVOICE,
    LIGHTNING_ADDRESS,
    ONCHAIN_ADDRESS,
    BIP21,
    UNSUPPORTED,
}

data class ParsedPaymentRequest(
    val kind: PaymentKind,
    val original: String,
    val network: BitcoinNetwork? = null,
    val amountSats: Long? = null,
    val reason: String? = null,
)
