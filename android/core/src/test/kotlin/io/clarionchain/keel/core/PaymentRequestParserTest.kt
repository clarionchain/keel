package io.clarionchain.keel.core

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class PaymentRequestParserTest {
    @Test
    fun fixturesFromSharedVectors() {
        val json = javaClass.classLoader!!
            .getResourceAsStream("payment-requests.json")!!
            .bufferedReader()
            .readText()
        val root = JSONObject(json)
        val vectors = root.getJSONArray("vectors")
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val parsed = PaymentRequestParser.parse(
                vector.getString("input"),
                BitcoinNetwork.SIGNET,
            )
            val expect = vector.getJSONObject("expect")
            val expectedKind = when (expect.getString("kind")) {
                "empty" -> PaymentKind.EMPTY
                "lightning_invoice" -> PaymentKind.LIGHTNING_INVOICE
                "onchain_address" -> PaymentKind.ONCHAIN_ADDRESS
                "bip21" -> PaymentKind.BIP21
                "unsupported" -> PaymentKind.UNSUPPORTED
                else -> error("unknown kind in ${vector.getString("id")}")
            }
            assertEquals(expectedKind, parsed.kind, vector.getString("id"))
            if (expect.has("reason")) {
                assertEquals(expect.getString("reason"), parsed.reason, vector.getString("id"))
            }
        }
    }

    @Test
    fun arkPrefixIsArkOnSignet() {
        val parsed = PaymentRequestParser.parse("ark1qtestplaceholder", BitcoinNetwork.SIGNET)
        assertEquals(PaymentKind.ARK_ADDRESS, parsed.kind)
    }

    @Test
    fun mainnetInvoiceRejectedOnSignet() {
        val parsed = PaymentRequestParser.parse("lnbc1anything", BitcoinNetwork.SIGNET)
        assertEquals(PaymentKind.UNSUPPORTED, parsed.kind)
        assertEquals("mainnet_on_signet", parsed.reason)
    }

    @Test
    fun bolt11AmountExtraction() {
        assertEquals(10L, PaymentRequestParser.bolt11AmountSats("lntbs100n"))
        assertEquals(100L, PaymentRequestParser.bolt11AmountSats("lntbs1000n"))
        assertEquals(100_000L, PaymentRequestParser.bolt11AmountSats("lntbs1m"))
        assertEquals(100_000L, PaymentRequestParser.bolt11AmountSats("lntbs1000u"))
        assertEquals(100_000_000L, PaymentRequestParser.bolt11AmountSats("lntbs1"))
        assertEquals(null, PaymentRequestParser.bolt11AmountSats("lntbs"))
    }

    @Test
    fun signetInvoiceCarriesAmount() {
        val parsed = PaymentRequestParser.parse("lntbs1000n1pjabcdef", BitcoinNetwork.SIGNET)
        assertEquals(PaymentKind.LIGHTNING_INVOICE, parsed.kind)
        assertEquals(100L, parsed.amountSats)
    }

    @Test
    fun regtestInvoiceAcceptedOnRegtest() {
        val parsed = PaymentRequestParser.parse("lnbcrt1000n1pjabcdef", BitcoinNetwork.REGTEST)
        assertEquals(PaymentKind.LIGHTNING_INVOICE, parsed.kind)
        assertEquals(100L, parsed.amountSats)
    }

    @Test
    fun regtestInvoiceRejectedOnSignet() {
        val parsed = PaymentRequestParser.parse("lnbcrt1000n1pjabcdef", BitcoinNetwork.SIGNET)
        assertEquals(PaymentKind.UNSUPPORTED, parsed.kind)
    }

    @Test
    fun regtestAddressAcceptedOnRegtest() {
        val parsed = PaymentRequestParser.parse("bcrt1qtestplaceholder", BitcoinNetwork.REGTEST)
        assertEquals(PaymentKind.ONCHAIN_ADDRESS, parsed.kind)
    }

    @Test
    fun regtestAddressRejectedOnSignet() {
        val parsed = PaymentRequestParser.parse("bcrt1qtestplaceholder", BitcoinNetwork.SIGNET)
        assertEquals(PaymentKind.UNSUPPORTED, parsed.kind)
    }
}
