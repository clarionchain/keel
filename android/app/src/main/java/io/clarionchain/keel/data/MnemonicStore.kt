package io.clarionchain.keel.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class MnemonicStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        PREFS_NAME,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(phrase: String) {
        prefs.edit().putString(KEY, phrase).apply()
    }

    fun load(): String? = prefs.getString(KEY, null)

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    fun isPresent(): Boolean = !load().isNullOrBlank()

    companion object {
        private const val PREFS_NAME = "keel_secure"
        private const val KEY = "recovery_phrase"
    }
}
