package io.clarionchain.keel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupManifestTest {

    private val manifest = BackupManifest(
        schemaVersion = 1,
        network = BitcoinNetwork.SIGNET,
        walletFingerprint = "fp123abc",
        generation = 7,
        createdAtEpochMs = 1_700_000_000_000,
        barkBindingVersion = "0.22.0+bark-0.6.2",
        appVersion = "0.5.0",
        contentSha256 = "a".repeat(64),
    )

    @Test
    fun jsonRoundTrip() {
        val parsed = parseBackupManifest(manifest.toJsonString())
        assertEquals(manifest, parsed)
    }

    @Test
    fun regtestNetworkSurvivesRoundTrip() {
        val parsed = parseBackupManifest(manifest.copy(network = BitcoinNetwork.REGTEST).toJsonString())
        assertEquals(BitcoinNetwork.REGTEST, parsed.network)
    }

    @Test
    fun rollbackRules() {
        // No live state: any backup installs.
        assertTrue(BackupRollback.canReplace(null, manifest))
        // Same or newer generation installs.
        assertTrue(BackupRollback.canReplace(7, manifest))
        assertTrue(BackupRollback.canReplace(6, manifest))
        // Older backup over newer live state: refused.
        assertFalse(BackupRollback.canReplace(8, manifest))
    }

    @Test
    fun invalidManifestRejected() {
        assertFailsWith<IllegalArgumentException> { manifest.copy(schemaVersion = 0) }
        assertFailsWith<IllegalArgumentException> { manifest.copy(generation = 0) }
        assertFailsWith<IllegalArgumentException> { manifest.copy(walletFingerprint = " ") }
        assertFailsWith<IllegalArgumentException> { manifest.copy(contentSha256 = "tooshort") }
    }
}
