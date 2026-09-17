package io.clarionchain.keel.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Background wallet maintenance (0.5.5): VTXOs on Second's signet server live
 * only 144 blocks (~24 h) and each delegated renewal is one-shot — the new
 * VTXO needs a fresh signature every cycle. This worker is that signature:
 * roughly every 12 h it wakes, opens the wallet (seed is Keystore-encrypted,
 * not biometric-bound, so no user interaction), syncs, claims Lightning
 * receives, re-arms delegated renewals for every VTXO, and closes again.
 *
 * If the foreground app currently holds the Bark datadir lock, opening fails —
 * that is fine: a live app maintains itself on every sync, so the run is
 * skipped without retry. Other failures (e.g. no connectivity despite the
 * constraint) retry with backoff.
 */
class MaintenanceWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repo = WalletRepository(applicationContext)
        if (!repo.hasStoredWallet()) return Result.success()
        return try {
            repo.openExisting()
            // Best-effort sync: a persistent sync failure (e.g. the server
            // forgot a stale pending board) must not block the renewals below.
            val syncFailed = runCatching { repo.sync() }.isFailure
            val claimed = runCatching { repo.claimAllLightningReceives() }.getOrDefault(0)
            repo.syncExitsAndBoards()
            // Exit progression + claiming are chain-only (no Ark server): they
            // must run even when the server sync is broken, or an exit could
            // never complete while the app is closed.
            runCatching { repo.progressExits() }
            val exitClaimed = runCatching { repo.claimExits() }.getOrNull() != null
            runCatching { repo.syncOnchain() }
            val (refreshedNow, _) = runCatching { repo.scheduleRefreshes() }.getOrDefault(0 to 0)
            if (claimed > 0 || refreshedNow > 0 || exitClaimed) runCatching { repo.autoBackupNow() }
            if (syncFailed && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
        } catch (e: Exception) {
            if (isDatadirLockContention(e)) {
                Result.success() // foreground app holds the wallet and maintains itself
            } else if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                Result.success() // next scheduled run is soon enough; don't hammer
            }
        } finally {
            // Never leak the datadir lock, or the next foreground open fails.
            if (repo.isOpen()) runCatching { repo.close() }
        }
    }

    private fun isDatadirLockContention(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            val msg = t.message?.lowercase() ?: ""
            if ("lock" in msg) return true
            t = t.cause
        }
        return false
    }

    companion object {
        private const val WORK_NAME = "wallet-maintenance"
        private const val REPEAT_HOURS = 12L
        private const val MAX_RETRIES = 3

        /** Idempotent: keeps the existing schedule if already enqueued. */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(REPEAT_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
