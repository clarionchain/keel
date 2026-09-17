package io.clarionchain.keel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VtxoExpiryTest {

    private fun vtxo(id: String, sats: Long, expiry: Long) = VtxoExpiryInput(id, sats, expiry)

    @Test
    fun expiredWhenTipReachesExpiryHeight() {
        // Server rejection observed in the wild: "vtxo expired at height 320792", tip 321078.
        assertEquals(
            VtxoExpiryStatus.EXPIRED,
            VtxoExpiry.status(expiryHeight = 320792, tipHeight = 321078, soonThreshold = 144),
        )
    }

    @Test
    fun tipExactlyAtExpiryIsExpired() {
        assertEquals(
            VtxoExpiryStatus.EXPIRED,
            VtxoExpiry.status(expiryHeight = 1000, tipHeight = 1000, soonThreshold = 144),
        )
    }

    @Test
    fun withinThresholdIsExpiringSoon() {
        assertEquals(
            VtxoExpiryStatus.EXPIRING_SOON,
            VtxoExpiry.status(expiryHeight = 1000, tipHeight = 999, soonThreshold = 144),
        )
        assertEquals(
            VtxoExpiryStatus.EXPIRING_SOON,
            VtxoExpiry.status(expiryHeight = 1000, tipHeight = 856, soonThreshold = 144),
        )
    }

    @Test
    fun beyondThresholdIsOk() {
        assertEquals(
            VtxoExpiryStatus.OK,
            VtxoExpiry.status(expiryHeight = 1000, tipHeight = 855, soonThreshold = 144),
        )
    }

    @Test
    fun classifySplitsAndSums() {
        val report = VtxoExpiry.classify(
            listOf(
                vtxo("a", 5_000, expiry = 321078 + 10_000), // ok
                vtxo("b", 3_000, expiry = 321078 + 100), // soon
                vtxo("c", 7_500, expiry = 320792), // expired
            ),
            tipHeight = 321078,
        )
        assertEquals(listOf("a"), report.ok.map { it.id })
        assertEquals(listOf("b"), report.expiringSoon.map { it.id })
        assertEquals(listOf("c"), report.expired.map { it.id })
        assertEquals(5_000, report.okSats)
        assertEquals(3_000, report.expiringSoonSats)
        assertEquals(7_500, report.expiredSats)
        assertTrue(report.needsRefresh)
        assertTrue(report.needsRecovery)
    }

    @Test
    fun classifyEmptyIsHealthy() {
        val report = VtxoExpiry.classify(emptyList(), tipHeight = 321078)
        assertFalse(report.needsRecovery)
        assertFalse(report.needsRefresh)
        assertEquals(0, report.okSats)
    }

    @Test
    fun expiredNotReportedAsSpendable() {
        val raw = BalanceBreakdown(
            spendable = Sats(12_500),
            pendingRound = Sats.ZERO,
            lightningLocked = Sats.ZERO,
            boardPending = Sats.ZERO,
            exitPending = Sats.ZERO,
            onchain = Sats.ZERO,
        )
        val report = VtxoExpiry.classify(
            listOf(vtxo("c", 7_500, expiry = 320792)),
            tipHeight = 321078,
        )
        val adjusted = raw.adjustedForExpiry(report)
        assertEquals(5_000, adjusted.spendable.value)
        assertEquals(7_500, adjusted.expired.value)
        assertEquals(12_500, adjusted.total.value)
    }

    @Test
    fun adjustmentFloorsAtZeroIfSdkAlreadyExcludedExpired() {
        val raw = BalanceBreakdown(
            spendable = Sats(5_000),
            pendingRound = Sats.ZERO,
            lightningLocked = Sats.ZERO,
            boardPending = Sats.ZERO,
            exitPending = Sats.ZERO,
            onchain = Sats.ZERO,
        )
        val report = VtxoExpiry.classify(
            listOf(vtxo("c", 7_500, expiry = 320792)),
            tipHeight = 321078,
        )
        val adjusted = raw.adjustedForExpiry(report)
        assertEquals(0, adjusted.spendable.value)
        assertEquals(7_500, adjusted.expired.value)
    }

    @Test
    fun expiringSoonIsSpendableSubsetNotDoubleCounted() {
        val raw = BalanceBreakdown(
            spendable = Sats(3_000),
            pendingRound = Sats.ZERO,
            lightningLocked = Sats.ZERO,
            boardPending = Sats.ZERO,
            exitPending = Sats.ZERO,
            onchain = Sats.ZERO,
        )
        val report = VtxoExpiry.classify(
            listOf(vtxo("b", 3_000, expiry = 321078 + 100)),
            tipHeight = 321078,
        )
        val adjusted = raw.adjustedForExpiry(report)
        assertEquals(3_000, adjusted.spendable.value)
        assertEquals(3_000, adjusted.expiringSoon.value)
        assertEquals(3_000, adjusted.total.value)
    }

    @Test
    fun expiredVtxoRejectionRecognized() {
        assertTrue(VtxoExpiry.isExpiredVtxoRejection("vtxo expired at height 320792"))
        assertTrue(VtxoExpiry.isExpiredVtxoRejection("Ark error: VTXO is expired"))
        assertTrue(VtxoExpiry.isExpiredVtxoRejection("input already expired (expiry height 320792)"))
    }

    @Test
    fun unrelatedErrorsNotMistakenForExpiry() {
        assertFalse(VtxoExpiry.isExpiredVtxoRejection(null))
        assertFalse(VtxoExpiry.isExpiredVtxoRejection("invoice expired"))
        assertFalse(VtxoExpiry.isExpiredVtxoRejection("insufficient funds"))
        assertFalse(VtxoExpiry.isExpiredVtxoRejection("timed out waiting for the Lightning payment"))
        assertFalse(VtxoExpiry.isExpiredVtxoRejection("connection refused"))
    }
}
