package io.clarionchain.keel.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Automatic on-device encrypted snapshots of the wallet state, written after
 * mutating operations (send, board, refresh, offboard, exit, Lightning claim).
 *
 * Encryption key lives in the Android Keystore (hardware-backed where
 * available, non-exportable, no user passphrase needed), so snapshots are
 * useless off this device. This is the *continuous* layer; off-device copies
 * remain the user's manual passphrase export (FullBackupEnvelope / KEELDB01).
 *
 * File format (autobackup/gen-<N>.bin):
 *   12 bytes  random GCM nonce
 *   rest      AES-256-GCM ciphertext of: 4-byte manifest length + manifest JSON
 *             + 2-byte phrase length + phrase + snapshot bytes
 *
 * Only the newest KEEP generations are retained.
 */
class AutoBackup(private val context: Context) {
    private val dir: File = File(context.noBackupFilesDir, "autobackup")

    fun latestGeneration(): Long? = dir.listFiles()
        ?.mapNotNull { it.name.removePrefix("gen-").removeSuffix(".bin").toLongOrNull() }
        ?.maxOrNull()

    fun hasBackup(): Boolean = latestGeneration() != null

    fun write(generation: Long, manifestJson: String, phrase: String, snapshot: ByteArray) {
        dir.mkdirs()
        val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8)
        val phraseBytes = phrase.toByteArray(Charsets.UTF_8)
        val payload = ByteArray(4 + manifestBytes.size + 2 + phraseBytes.size + snapshot.size)
        payload[0] = (manifestBytes.size ushr 24).toByte()
        payload[1] = (manifestBytes.size ushr 16).toByte()
        payload[2] = (manifestBytes.size ushr 8).toByte()
        payload[3] = manifestBytes.size.toByte()
        manifestBytes.copyInto(payload, 4)
        payload[4 + manifestBytes.size] = (phraseBytes.size ushr 8).toByte()
        payload[5 + manifestBytes.size] = phraseBytes.size.toByte()
        phraseBytes.copyInto(payload, 6 + manifestBytes.size)
        snapshot.copyInto(payload, 6 + manifestBytes.size + phraseBytes.size)

        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(payload)

        val out = File(dir, "gen-$generation.bin")
        val tmp = File(dir, "gen-$generation.tmp")
        tmp.writeBytes(nonce + ciphertext)
        check(tmp.renameTo(out)) { "could not finalize auto-backup" }
        prune()
    }

    /** Returns (manifestJson, phrase, snapshot) of the newest snapshot. */
    fun readLatest(): Triple<String, String, ByteArray>? {
        val generation = latestGeneration() ?: return null
        val bytes = File(dir, "gen-$generation.bin").readBytes()
        require(bytes.size > 12 + 16) { "corrupt auto-backup" }
        val nonce = bytes.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, nonce))
        val payload = cipher.doFinal(bytes.copyOfRange(12, bytes.size))

        val mLen = ((payload[0].toInt() and 0xFF) shl 24) or
            ((payload[1].toInt() and 0xFF) shl 16) or
            ((payload[2].toInt() and 0xFF) shl 8) or
            (payload[3].toInt() and 0xFF)
        val manifestJson = String(payload, 4, mLen, Charsets.UTF_8)
        val pLen = ((payload[4 + mLen].toInt() and 0xFF) shl 8) or (payload[5 + mLen].toInt() and 0xFF)
        val phrase = String(payload, 6 + mLen, pLen, Charsets.UTF_8)
        val snapshot = payload.copyOfRange(6 + mLen + pLen, payload.size)
        return Triple(manifestJson, phrase, snapshot)
    }

    fun clear() {
        dir.deleteRecursively()
    }

    private fun prune() {
        val files = dir.listFiles()?.filter { it.name.startsWith("gen-") } ?: return
        files.sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
    }

    private fun key(): javax.crypto.SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ALIAS = "keel_auto_backup_v1"
        private const val KEEP = 3
    }
}
