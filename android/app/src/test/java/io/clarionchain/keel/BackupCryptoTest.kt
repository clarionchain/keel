package io.clarionchain.keel.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val passphrase = "correct horse battery staple".toCharArray()

    @Test
    fun `round trip restores the exact plaintext`() {
        val envelope = BackupCrypto.encrypt(phrase.toByteArray(Charsets.UTF_8), passphrase, iterations = 5_000)
        val decrypted = BackupCrypto.decrypt(envelope, passphrase)
        assertEquals(phrase, decrypted.toString(Charsets.UTF_8))
    }

    @Test
    fun `round trip with default iteration count`() {
        val envelope = BackupCrypto.encrypt(phrase.toByteArray(Charsets.UTF_8), passphrase)
        val decrypted = BackupCrypto.decrypt(envelope, passphrase)
        assertArrayEquals(phrase.toByteArray(Charsets.UTF_8), decrypted)
    }

    @Test
    fun `same input produces different envelopes`() {
        val a = BackupCrypto.encrypt(phrase.toByteArray(Charsets.UTF_8), passphrase, iterations = 5_000)
        val b = BackupCrypto.encrypt(phrase.toByteArray(Charsets.UTF_8), passphrase, iterations = 5_000)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `wrong passphrase fails`() {
        val envelope = BackupCrypto.encrypt(phrase.toByteArray(Charsets.UTF_8), passphrase, iterations = 5_000)
        assertThrows(Exception::class.java) {
            BackupCrypto.decrypt(envelope, "wrong passphrase".toCharArray())
        }
    }

    @Test
    fun `tampered ciphertext fails`() {
        val envelope = BackupCrypto.encrypt(phrase.toByteArray(Charsets.UTF_8), passphrase, iterations = 5_000)
        envelope[envelope.size - 1] = (envelope[envelope.size - 1].toInt() xor 0x01).toByte()
        assertThrows(Exception::class.java) {
            BackupCrypto.decrypt(envelope, passphrase)
        }
    }

    @Test
    fun `foreign file is rejected`() {
        val notABackup = "hello world".toByteArray(Charsets.UTF_8)
        val err = assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.decrypt(notABackup, passphrase)
        }
        assertTrue(err.message!!.contains("not a Keel backup file"))
    }

    @Test
    fun `empty passphrase is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCrypto.encrypt(phrase.toByteArray(Charsets.UTF_8), CharArray(0), iterations = 5_000)
        }
    }
}
