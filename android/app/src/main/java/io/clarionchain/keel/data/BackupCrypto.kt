package io.clarionchain.keel.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-encrypted backup envelope for the recovery phrase.
 *
 * Binary format (no external deps, stable across app versions):
 *   8 bytes  magic "KEELBK01" (format + version)
 *   1 byte   salt length, then salt
 *   1 byte   nonce length, then nonce
 *   4 bytes  PBKDF2 iteration count (big-endian)
 *   rest     AES-256-GCM ciphertext (tag appended by the JCE provider)
 *
 * The passphrase is never stored. Wrong passphrase or any tampering fails
 * at the GCM tag check.
 *
 * Pure JVM (no android.* imports) so it runs in local unit tests.
 */
object BackupCrypto {
    private val MAGIC = "KEELBK01".toByteArray(Charsets.US_ASCII)
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val DEFAULT_ITERATIONS = 600_000
    private const val SALT_LENGTH = 16
    private const val NONCE_LENGTH = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    fun encrypt(
        plaintext: ByteArray,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        require(iterations > 0) { "iterations must be positive" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val nonce = ByteArray(NONCE_LENGTH).also { random.nextBytes(it) }
        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        val out = ByteArray(MAGIC.size + 1 + salt.size + 1 + nonce.size + 4 + ciphertext.size)
        var pos = 0
        MAGIC.copyInto(out, pos); pos += MAGIC.size
        out[pos++] = salt.size.toByte()
        salt.copyInto(out, pos); pos += salt.size
        out[pos++] = nonce.size.toByte()
        nonce.copyInto(out, pos); pos += nonce.size
        out[pos++] = (iterations ushr 24).toByte()
        out[pos++] = (iterations ushr 16).toByte()
        out[pos++] = (iterations ushr 8).toByte()
        out[pos++] = iterations.toByte()
        ciphertext.copyInto(out, pos)
        return out
    }

    fun decrypt(envelope: ByteArray, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        var pos = 0
        require(envelope.size >= MAGIC.size + 2 + 4) { "not a Keel backup file" }
        val magic = envelope.copyOfRange(pos, pos + MAGIC.size)
        require(magic.contentEquals(MAGIC)) { "not a Keel backup file (bad magic)" }
        pos += MAGIC.size

        val saltLength = envelope[pos++].toInt() and 0xFF
        require(saltLength > 0 && envelope.size >= pos + saltLength + 1) { "corrupt backup file" }
        val salt = envelope.copyOfRange(pos, pos + saltLength)
        pos += saltLength

        val nonceLength = envelope[pos++].toInt() and 0xFF
        require(nonceLength > 0 && envelope.size >= pos + nonceLength + 4) { "corrupt backup file" }
        val nonce = envelope.copyOfRange(pos, pos + nonceLength)
        pos += nonceLength

        val iterations = ((envelope[pos].toInt() and 0xFF) shl 24) or
            ((envelope[pos + 1].toInt() and 0xFF) shl 16) or
            ((envelope[pos + 2].toInt() and 0xFF) shl 8) or
            (envelope[pos + 3].toInt() and 0xFF)
        pos += 4
        require(iterations > 0) { "corrupt backup file" }

        val ciphertext = envelope.copyOfRange(pos, envelope.size)
        require(ciphertext.isNotEmpty()) { "corrupt backup file" }

        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        // AEADBadTagException here means wrong passphrase or tampered file.
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
