package io.clarionchain.keel.data

import io.clarionchain.keel.core.BackupManifest
import io.clarionchain.keel.core.parseBackupManifest
import java.security.MessageDigest

/**
 * Full wallet-state backup envelope (KEELDB01). Complements the seed-only
 * KEELBK01 file: the payload carries the recovery phrase AND the wallet
 * database snapshot, so a restore needs no Ark server cooperation.
 *
 * Binary format (no external deps, stable across app versions):
 *   8 bytes  magic "KEELDB01" (format + version)
 *   4 bytes  manifest JSON length (big-endian)
 *   N bytes  manifest JSON — PLAINTEXT, so generation/network/fingerprint can
 *            be checked (and shown) before asking for the passphrase
 *   rest     BackupCrypto (KEELBK01) envelope encrypting the payload
 *
 * Payload (plaintext before encryption):
 *   2 bytes  phrase length (big-endian)
 *   N bytes  BIP39 phrase UTF-8
 *   rest     wallet data snapshot (zip of the Bark datadir)
 *
 * Pure JVM (no android.* imports) so it runs in local unit tests.
 */
object FullBackupEnvelope {
    private val MAGIC = "KEELDB01".toByteArray(Charsets.US_ASCII)

    /** Returns true if the bytes start with the full-backup magic. */
    fun isFullBackup(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)

    fun pack(
        manifest: BackupManifest,
        phrase: String,
        snapshot: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        val phraseBytes = phrase.toByteArray(Charsets.UTF_8)
        require(phraseBytes.size <= 0xFFFF) { "phrase unreasonably long" }
        val payload = ByteArray(2 + phraseBytes.size + snapshot.size)
        payload[0] = (phraseBytes.size ushr 8).toByte()
        payload[1] = phraseBytes.size.toByte()
        phraseBytes.copyInto(payload, 2)
        snapshot.copyInto(payload, 2 + phraseBytes.size)

        val manifestBytes = manifest.toJsonString().toByteArray(Charsets.UTF_8)
        val encrypted = BackupCrypto.encrypt(payload, passphrase)
        val out = ByteArray(MAGIC.size + 4 + manifestBytes.size + encrypted.size)
        MAGIC.copyInto(out, 0)
        out[MAGIC.size] = (manifestBytes.size ushr 24).toByte()
        out[MAGIC.size + 1] = (manifestBytes.size ushr 16).toByte()
        out[MAGIC.size + 2] = (manifestBytes.size ushr 8).toByte()
        out[MAGIC.size + 3] = manifestBytes.size.toByte()
        manifestBytes.copyInto(out, MAGIC.size + 4)
        encrypted.copyInto(out, MAGIC.size + 4 + manifestBytes.size)
        return out
    }

    /** Reads the plaintext manifest without decrypting. */
    fun readManifest(bytes: ByteArray): BackupManifest {
        require(isFullBackup(bytes)) { "not a Keel full backup file (bad magic)" }
        require(bytes.size >= MAGIC.size + 4) { "corrupt backup file" }
        val len = ((bytes[MAGIC.size].toInt() and 0xFF) shl 24) or
            ((bytes[MAGIC.size + 1].toInt() and 0xFF) shl 16) or
            ((bytes[MAGIC.size + 2].toInt() and 0xFF) shl 8) or
            (bytes[MAGIC.size + 3].toInt() and 0xFF)
        require(len > 0 && bytes.size >= MAGIC.size + 4 + len + 1) { "corrupt backup file" }
        return parseBackupManifest(String(bytes, MAGIC.size + 4, len, Charsets.UTF_8))
    }

    fun unpack(bytes: ByteArray, passphrase: CharArray): FullBackup {
        val manifest = readManifest(bytes)
        val len = ((bytes[MAGIC.size].toInt() and 0xFF) shl 24) or
            ((bytes[MAGIC.size + 1].toInt() and 0xFF) shl 16) or
            ((bytes[MAGIC.size + 2].toInt() and 0xFF) shl 8) or
            (bytes[MAGIC.size + 3].toInt() and 0xFF)
        val envelope = bytes.copyOfRange(MAGIC.size + 4 + len, bytes.size)
        val payload = BackupCrypto.decrypt(envelope, passphrase)
        require(payload.size >= 2) { "corrupt backup payload" }
        val phraseLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
        require(phraseLen > 0 && payload.size > 2 + phraseLen) { "corrupt backup payload" }
        val phrase = String(payload, 2, phraseLen, Charsets.UTF_8)
        val snapshot = payload.copyOfRange(2 + phraseLen, payload.size)
        require(sha256Hex(snapshot) == manifest.contentSha256) { "backup content hash mismatch" }
        return FullBackup(manifest, phrase, snapshot)
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}

data class FullBackup(
    val manifest: BackupManifest,
    val phrase: String,
    val snapshot: ByteArray,
)
