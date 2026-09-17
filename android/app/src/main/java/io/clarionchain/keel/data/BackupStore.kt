package io.clarionchain.keel.data

import android.content.Context

/**
 * Persists the monotonic backup generation per wallet fingerprint.
 * Not secret — plain SharedPreferences. Used for rollback detection:
 * a backup with generation N must never replace live state with generation > N.
 */
class BackupStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("keel_backup_generations", Context.MODE_PRIVATE)

    fun lastGeneration(fingerprint: String): Long? =
        prefs.getLong(fingerprint, 0L).takeIf { it > 0L }

    /** Monotonic: only ever moves forward. Returns the stored generation. */
    fun record(fingerprint: String, generation: Long): Long {
        val current = lastGeneration(fingerprint) ?: 0L
        val next = maxOf(current, generation)
        prefs.edit().putLong(fingerprint, next).apply()
        return next
    }

    fun nextGeneration(fingerprint: String): Long = (lastGeneration(fingerprint) ?: 0L) + 1L

    fun clear(fingerprint: String) {
        prefs.edit().remove(fingerprint).apply()
    }
}
