package io.clarionchain.keel

import io.clarionchain.keel.core.BackupManifest
import io.clarionchain.keel.core.BitcoinNetwork
import io.clarionchain.keel.data.BackupCrypto
import io.clarionchain.keel.data.FullBackupEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FullBackupEnvelopeTest {

    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

    private fun manifest(generation: Long = 3, snapshot: ByteArray = ByteArray(2048) { it.toByte() }) = BackupManifest(
        schemaVersion = 1,
        network = BitcoinNetwork.SIGNET,
        walletFingerprint = "fp123abc",
        generation = generation,
        createdAtEpochMs = 1_700_000_000_000,
        barkBindingVersion = "0.22.0+bark-0.6.2",
        appVersion = "0.5.0",
        contentSha256 = FullBackupEnvelope.sha256Hex(snapshot),
    )

    @Test
    fun roundTrip() {
        val snapshot = ByteArray(2048) { (it * 7).toByte() }
        val packed = FullBackupEnvelope.pack(manifest(snapshot = snapshot), phrase, snapshot, "hunter2".toCharArray())
        assertTrue(FullBackupEnvelope.isFullBackup(packed))

        val backup = FullBackupEnvelope.unpack(packed, "hunter2".toCharArray())
        assertEquals(phrase, backup.phrase)
        assertTrue(snapshot.contentEquals(backup.snapshot))
        assertEquals(3L, backup.manifest.generation)
        assertEquals("fp123abc", backup.manifest.walletFingerprint)
        assertEquals(BitcoinNetwork.SIGNET, backup.manifest.network)
    }

    @Test
    fun manifestReadableWithoutPassphrase() {
        val snapshot = ByteArray(128) { it.toByte() }
        val packed = FullBackupEnvelope.pack(manifest(generation = 9, snapshot = snapshot), phrase, snapshot, "pw".toCharArray())
        val manifest = FullBackupEnvelope.readManifest(packed)
        assertEquals(9L, manifest.generation)
        assertEquals("fp123abc", manifest.walletFingerprint)
    }

    @Test
    fun wrongPassphraseFails() {
        val snapshot = ByteArray(128) { it.toByte() }
        val packed = FullBackupEnvelope.pack(manifest(snapshot = snapshot), phrase, snapshot, "right".toCharArray())
        assertThrows(Exception::class.java) { FullBackupEnvelope.unpack(packed, "wrong".toCharArray()) }
    }

    @Test
    fun tamperedCiphertextFails() {
        val snapshot = ByteArray(128) { it.toByte() }
        val packed = FullBackupEnvelope.pack(manifest(snapshot = snapshot), phrase, snapshot, "pw".toCharArray())
        packed[packed.size - 1] = (packed[packed.size - 1].toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) { FullBackupEnvelope.unpack(packed, "pw".toCharArray()) }
    }

    @Test
    fun tamperedManifestFailsHashCheck() {
        val snapshot = ByteArray(128) { it.toByte() }
        val packed = FullBackupEnvelope.pack(manifest(generation = 3, snapshot = snapshot), phrase, snapshot, "pw".toCharArray())
        // Swap the plaintext manifest for one with a different generation: unpack must
        // still fail because the snapshot hash no longer matches... actually generation
        // is not hashed; content hash is. A manifest claiming a different content fails.
        val evil = manifest(generation = 99, snapshot = ByteArray(128) { 0 })
        val evilBytes = evil.toJsonString().toByteArray(Charsets.UTF_8)
        val origLen = ((packed[8].toInt() and 0xFF) shl 24) or ((packed[9].toInt() and 0xFF) shl 16) or
            ((packed[10].toInt() and 0xFF) shl 8) or (packed[11].toInt() and 0xFF)
        val rebuilt = packed.copyOfRange(0, 12) + evilBytes + packed.copyOfRange(12 + origLen, packed.size)
        // Fix up the length header.
        rebuilt[8] = (evilBytes.size ushr 24).toByte()
        rebuilt[9] = (evilBytes.size ushr 16).toByte()
        rebuilt[10] = (evilBytes.size ushr 8).toByte()
        rebuilt[11] = evilBytes.size.toByte()
        assertThrows(Exception::class.java) { FullBackupEnvelope.unpack(rebuilt, "pw".toCharArray()) }
    }

    @Test
    fun seedBackupIsNotDetectedAsFull() {
        val seedOnly = BackupCrypto.encrypt(phrase.toByteArray(Charsets.UTF_8), "pw".toCharArray())
        assertFalse(FullBackupEnvelope.isFullBackup(seedOnly))
        assertThrows(Exception::class.java) { FullBackupEnvelope.readManifest(seedOnly) }
    }
}
