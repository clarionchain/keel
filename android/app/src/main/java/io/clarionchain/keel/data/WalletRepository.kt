package io.clarionchain.keel.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import io.clarionchain.keel.BuildConfig
import io.clarionchain.keel.core.BackupManifest
import io.clarionchain.keel.core.BackupRollback
import io.clarionchain.keel.core.BalanceBreakdown
import io.clarionchain.keel.core.BitcoinNetwork
import io.clarionchain.keel.core.PsbtExtract
import io.clarionchain.keel.core.Sats
import io.clarionchain.keel.core.VtxoExpiry
import io.clarionchain.keel.core.VtxoExpiryInput
import io.clarionchain.keel.core.VtxoExpiryReport
import io.clarionchain.keel.core.adjustedForExpiry
import io.clarionchain.keel.core.parseBackupManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.bark.Config
import uniffi.bark.ExitState
import uniffi.bark.ExitVtxo
import uniffi.bark.LightningInvoice
import uniffi.bark.LightningSendStatus
import uniffi.bark.Movement
import uniffi.bark.Network
import uniffi.bark.OnchainWallet
import uniffi.bark.PendingBoard
import uniffi.bark.Vtxo
import uniffi.bark.Wallet
import uniffi.bark.WalletOpenArgs
import uniffi.bark.generateMnemonic
import uniffi.bark.validateMnemonic
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WalletRepository(context: Context) {
    private val appContext = context.applicationContext
    private val mnemonicStore = MnemonicStore(appContext)
    private val backupStore = BackupStore(appContext)
    private val autoBackup = AutoBackup(appContext)
    private val mutex = Mutex()
    private var wallet: Wallet? = null
    private var onchain: OnchainWallet? = null
    private var openedFingerprint: String? = null

    fun dataDir(): File = File(appContext.noBackupFilesDir, "bark").apply { mkdirs() }

    fun hasStoredWallet(): Boolean {
        return mnemonicStore.isPresent() && File(dataDir(), "db.sqlite").exists()
    }

    suspend fun generatePhrase(): String = withContext(Dispatchers.IO) {
        generateMnemonic()
    }

    fun isValidPhrase(phrase: String): Boolean = validateMnemonic(phrase.trim())

    suspend fun createFromPhrase(phrase: String, recoverFromServer: Boolean): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val trimmed = phrase.trim()
            require(validateMnemonic(trimmed)) { "recovery phrase is not valid BIP39" }
            openLocked(
                phrase = trimmed,
                createIfMissing = true,
                skipRecovery = !recoverFromServer,
            )
        }
    }

    suspend fun openExisting(): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val phrase = mnemonicStore.load() ?: error("no recovery phrase in secure storage")
            openLocked(
                phrase = phrase,
                createIfMissing = false,
                skipRecovery = true,
            )
        }
    }

    /** True when the wallet is already open in this process (e.g. locked UI only). */
    fun isOpen(): Boolean = wallet != null

    /** Closes the wallet and releases the Bark datadir lock. */
    suspend fun close() = mutex.withLock {
        withContext(Dispatchers.IO) {
            wallet?.close()
            wallet = null
            onchain = null
            openedFingerprint = null
        }
    }

    fun currentFingerprint(): String? = openedFingerprint

    suspend fun sync() = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().sync() }
    }

    suspend fun balance(): BalanceBreakdown = mutex.withLock {
        withContext(Dispatchers.IO) {
            val b = requireWallet().balance()
            val raw = BalanceBreakdown(
                spendable = Sats(b.spendableSats.toLong()),
                pendingRound = Sats(b.pendingInRoundSats.toLong()),
                lightningLocked = Sats(
                    b.pendingLightningSendSats.toLong() + b.claimableLightningReceiveSats.toLong(),
                ),
                boardPending = Sats(b.pendingBoardSats.toLong()),
                exitPending = Sats(b.pendingExitSats.toLong()),
                // Best-effort read of the on-chain wallet cache (synced in
                // refreshHome); claimed exits land here.
                onchain = runCatching { Sats(requireOnchain().balance().confirmedSats.toLong()) }
                    .getOrDefault(Sats.ZERO),
            )
            // Bark counts expired VTXOs in spendableSats; Keel never reports them as spendable.
            // Bark 0.6.2 also keeps exiting VTXOs in spendableSats — move those out too,
            // since they are already reported under exitPending (no double count).
            runCatching {
                val w = requireWallet()
                val exiting = activelyExitingIds(w)
                val exitingSats = w.spendableVtxos()
                    .filter { it.id in exiting }
                    .sumOf { it.amountSats.toLong() }
                val adj = raw.adjustedForExpiry(expiryReportLocked())
                if (exitingSats <= 0L) adj
                else adj.copy(
                    spendable = if (adj.spendable.value >= exitingSats) Sats(adj.spendable.value - exitingSats) else Sats.ZERO,
                )
            }.getOrDefault(raw)
        }
    }

    suspend fun tipHeight(): Long = mutex.withLock {
        withContext(Dispatchers.IO) { requireOnchain().tipHeight().toLong() }
    }

    /** Classifies the spendable VTXO set against the synced chain tip. Call after sync(). */
    suspend fun expiryReport(): VtxoExpiryReport = mutex.withLock {
        withContext(Dispatchers.IO) { expiryReportLocked() }
    }

    /**
     * Ids of VTXOs with an active exit. They are leaving Ark, so Ark-side
     * expiry no longer applies to them: excluding them keeps the expiry
     * banner and refresh scheduling from contradicting the exit-in-progress
     * state (bark 0.6.2 still lists exiting VTXOs as spendable).
     */
    private suspend fun activelyExitingIds(w: Wallet): Set<String> =
        runCatching {
            w.getExitVtxos()
                .filter { it.state !is ExitState.Canceled && it.state !is ExitState.VtxoAlreadySpent }
                .map { it.vtxoId }
                .toSet()
        }.getOrDefault(emptySet())

    private suspend fun expiryReportLocked(): VtxoExpiryReport {
        val w = requireWallet()
        val tip = requireOnchain().tipHeight().toLong()
        val exiting = activelyExitingIds(w)
        val inputs = w.spendableVtxos()
            .filter { it.id !in exiting }
            .map {
                VtxoExpiryInput(id = it.id, amountSats = it.amountSats.toLong(), expiryHeight = it.expiryHeight.toLong())
            }
        return VtxoExpiry.classify(inputs, tip)
    }

    /**
     * Foreground refresh of every VTXO the wallet policy says is due (expiring soon).
     * Returns the refresh round txid, or null when nothing was due.
     */
    suspend fun refreshDueVtxos(): String? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val w = requireWallet()
            val exiting = activelyExitingIds(w)
            val due = w.getVtxosToRefresh().filter { it.id !in exiting }
            if (due.isEmpty()) return@withContext null
            w.refreshVtxos(due.map { it.id })
        }
    }

    /**
     * Permanent expiry protection (0.5.4): at every sync, hand the server a
     * signed delegated "renewal appointment" for every spendable VTXO.
     * VTXOs inside the soon-threshold are refreshed in the next round; all
     * others are scheduled for ~1 day before their own expiry. The server
     * carries delegated participations through the round by itself, so funds
     * are refreshed even while the app is closed. Expired VTXOs are skipped
     * (they can only be recovered on-chain via emergency exit).
     *
     * Returns (refreshedNow, scheduled) counts.
     */
    suspend fun scheduleRefreshes(): Pair<Int, Int> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val w = requireWallet()
            val tip = requireOnchain().tipHeight().toLong()
            val inRound = runCatching { w.pendingRoundInputVtxos().map { it.id }.toSet() }
                .getOrDefault(emptySet())
            val exiting = activelyExitingIds(w)
            val due = mutableListOf<String>()
            val byHeight = sortedMapOf<Long, MutableList<String>>()
            for (v in w.spendableVtxos()) {
                if (v.id in inRound || v.id in exiting) continue
                val expiry = v.expiryHeight.toLong()
                if (tip >= expiry) continue // expired — not refreshable
                val at = expiry - VtxoExpiry.DEFAULT_SOON_THRESHOLD_BLOCKS
                if (at <= tip) due.add(v.id) else byHeight.getOrPut(at) { mutableListOf() }.add(v.id)
            }
            var refreshedNow = 0
            var scheduled = 0
            if (due.isNotEmpty()) {
                runCatching { w.refreshVtxosDelegated(due) }.onSuccess { refreshedNow = due.size }
            }
            for ((height, ids) in byHeight) {
                runCatching { w.refreshVtxosScheduled(ids, height.toUInt()) }
                    .onSuccess { scheduled += ids.size }
            }
            refreshedNow to scheduled
        }
    }

    suspend fun newArkAddress(): String = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().newAddress() }
    }

    suspend fun estimateArkFee(amount: Sats): Sats = mutex.withLock {
        withContext(Dispatchers.IO) {
            val estimate = requireWallet().estimateArkoorPaymentFee(amount.value.toULong())
            Sats(estimate.feeSats.toLong())
        }
    }

    suspend fun sendArk(address: String, amount: Sats) = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireWallet().sendArkoorPayment(address, amount.value.toULong())
        }
    }

    suspend fun validateArkAddress(address: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().validateArkoorAddress(address) }
    }

    suspend fun history(): List<Movement> = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().history() }
    }

    suspend fun createLightningInvoice(amount: Sats, description: String?): LightningInvoice = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireWallet().bolt11Invoice(amount.value.toULong(), description, null)
        }
    }

    suspend fun isInvoicePaid(paymentHash: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().isInvoicePaid(paymentHash) }
    }

    suspend fun claimAllLightningReceives(): Int = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().tryClaimAllLightningReceives(false).size }
    }

    suspend fun estimateLightningSendFee(amount: Sats): Sats = mutex.withLock {
        withContext(Dispatchers.IO) {
            Sats(requireWallet().estimateLightningSendFee(amount.value.toULong()).feeSats.toLong())
        }
    }

    suspend fun payLightningInvoice(invoice: String, amount: Sats?): LightningSendStatus = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireWallet().payLightningInvoice(invoice, amount?.value?.toULong(), true)
        }
    }

    suspend fun boardFundingAddress(): String = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().boardFundingAddress().address }
    }

    suspend fun boardAll(): PendingBoard = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().boardAll() }
    }

    suspend fun spendableVtxos(): List<Vtxo> = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().spendableVtxos() }
    }

    suspend fun estimateOffboardFee(address: String, vtxoIds: List<String>): Sats = mutex.withLock {
        withContext(Dispatchers.IO) {
            Sats(requireWallet().estimateOffboardFee(address, vtxoIds).feeSats.toLong())
        }
    }

    suspend fun offboardVtxos(vtxoIds: List<String>, address: String): String = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().offboardVtxos(vtxoIds, address).txid }
    }

    suspend fun startExitForEntireWallet() = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().startExitForEntireWallet() }
    }

    suspend fun exitVtxos(): List<ExitVtxo> = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().getExitVtxos() }
    }

    suspend fun hasPendingExits(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().hasPendingExits() }
    }

    suspend fun syncExitsAndBoards() = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { requireWallet().syncPendingBoards() }
            runCatching { requireWallet().syncExits() }
        }
    }

    /**
     * Advance exit state machines (claim detection, CPFP broadcasts). Talks to
     * the chain source only — never the Ark server — so it works even when the
     * server sync is broken.
     */
    suspend fun progressExits() = mutex.withLock {
        withContext(Dispatchers.IO) { requireWallet().progressExits(null) }
    }

    /**
     * Claim every claimable exit back to our own on-chain wallet and broadcast
     * the claim transaction. Returns (txid, claimedSats), or null when nothing
     * is claimable. Chain-only: an exit can always complete without the server.
     */
    suspend fun claimExits(): Pair<String, Long>? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val w = requireWallet()
            val claimable = w.listClaimableExits()
            if (claimable.isEmpty()) return@withContext null
            val address = requireOnchain().newAddress()
            val claim = w.drainExits(claimable.map { it.vtxoId }, address, null)
            val txid = w.broadcastTx(PsbtExtract.extractTxHex(claim.psbtBase64))
            txid to claimable.sumOf { it.amountSats.toLong() }
        }
    }

    /** Sync the on-chain wallet against the chain source; returns confirmed sats. */
    suspend fun syncOnchain(): Long = mutex.withLock {
        withContext(Dispatchers.IO) { requireOnchain().sync().toLong() }
    }

    suspend fun deleteWallet() = mutex.withLock {
        withContext(Dispatchers.IO) {
            wallet?.close()
            wallet = null
            onchain = null
            openedFingerprint?.let { backupStore.clear(it) }
            openedFingerprint = null
            autoBackup.clear()
            mnemonicStore.clear()
            dataDir().deleteRecursively()
            dataDir().mkdirs()
        }
    }

    suspend fun exportEncryptedBackup(uri: Uri, passphrase: CharArray) = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 26) { "Encrypted backup requires Android 8.0 or newer" }
        val phrase = mnemonicStore.load() ?: error("no recovery phrase stored on this device")
        val envelope = BackupCrypto.encrypt(phrase.toByteArray(Charsets.UTF_8), passphrase)
        appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(envelope) }
            ?: error("could not open the selected destination")
    }

    // ---- Full wallet-state backups (KEELDB01): phrase + database snapshot ----

    /** Reads the plaintext manifest of a full-backup file without decrypting it. */
    suspend fun readFullBackupManifest(uri: Uri): BackupManifest = withContext(Dispatchers.IO) {
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("could not read the selected file")
        FullBackupEnvelope.readManifest(bytes)
    }

    suspend fun exportFullBackup(uri: Uri, passphrase: CharArray) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val phrase = mnemonicStore.load() ?: error("no recovery phrase stored on this device")
            val fp = openedFingerprint ?: error("wallet is closed")
            val snapshot = snapshotDatadirLocked()
            val generation = backupStore.nextGeneration(fp)
            val manifest = newManifest(fp, generation, snapshot)
            val bytes = FullBackupEnvelope.pack(manifest, phrase, snapshot, passphrase)
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: error("could not open the selected destination")
            backupStore.record(fp, generation)
        }
    }

    /**
     * Installs a full backup: validates network/fingerprint/rollback BEFORE touching
     * the live wallet, then swaps the datadir and reopens. Returns the fingerprint.
     */
    suspend fun importFullBackup(uri: Uri, passphrase: CharArray): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("could not read the selected file")
            val backup = FullBackupEnvelope.unpack(bytes, passphrase)
            checkInstallable(backup.manifest)
            val fp = installSnapshotLocked(backup.snapshot, backup.phrase)
            backupStore.record(backup.manifest.walletFingerprint, backup.manifest.generation)
            fp
        }
    }

    /** Continuous on-device layer: encrypted with a Keystore key, triggered after mutations. */
    suspend fun autoBackupNow(): Long? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val phrase = mnemonicStore.load() ?: return@withContext null
            val fp = openedFingerprint ?: return@withContext null
            val snapshot = snapshotDatadirLocked()
            val generation = backupStore.nextGeneration(fp)
            autoBackup.write(generation, newManifest(fp, generation, snapshot).toJsonString(), phrase, snapshot)
            backupStore.record(fp, generation)
            generation
        }
    }

    fun hasAutoBackup(): Boolean = autoBackup.hasBackup()

    /** Restores the newest local auto-backup (Keystore-decrypted, no passphrase). */
    suspend fun restoreFromAutoBackup(): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val (manifestJson, phrase, snapshot) = autoBackup.readLatest()
                ?: error("no local auto-backup found")
            val manifest = parseBackupManifest(manifestJson)
            checkInstallable(manifest)
            installSnapshotLocked(snapshot, phrase)
        }
    }

    private fun checkInstallable(manifest: BackupManifest) {
        check(manifest.network.name.equals(BuildConfig.DEFAULT_NETWORK, ignoreCase = true)) {
            "backup is for ${manifest.network.name.lowercase()}, this build is on ${BuildConfig.DEFAULT_NETWORK}"
        }
        val liveFp = openedFingerprint
        if (liveFp != null && liveFp != manifest.walletFingerprint) {
            error("backup belongs to a different wallet - delete this wallet before restoring it")
        }
        val liveGen = backupStore.lastGeneration(manifest.walletFingerprint)
        check(BackupRollback.canReplace(liveGen, manifest)) {
            "backup (generation ${manifest.generation}) is older than the current wallet state (generation $liveGen) - refusing to roll back"
        }
    }

    private fun newManifest(fingerprint: String, generation: Long, snapshot: ByteArray): BackupManifest =
        BackupManifest(
            schemaVersion = 1,
            network = if (BuildConfig.DEFAULT_NETWORK == "regtest") BitcoinNetwork.REGTEST else BitcoinNetwork.SIGNET,
            walletFingerprint = fingerprint,
            generation = generation,
            createdAtEpochMs = System.currentTimeMillis(),
            barkBindingVersion = BuildConfig.BARK_BINDING,
            appVersion = BuildConfig.VERSION_NAME,
            contentSha256 = FullBackupEnvelope.sha256Hex(snapshot),
        )

    /**
     * Consistent zip of the Bark datadir. The db is snapshotted with VACUUM INTO
     * (never a live copy); on Android < 10 (SQLite < 3.27) we checkpoint the WAL
     * and copy while the wallet is idle (all wallet calls hold this mutex).
     */
    private fun snapshotDatadirLocked(): ByteArray {
        val dir = dataDir()
        val dbFile = File(dir, "db.sqlite")
        require(dbFile.exists()) { "wallet database missing" }
        val dbSnapshot = File(appContext.cacheDir, "keel-dbsnapshot.sqlite").apply { delete() }
        val sqlite = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                sqlite.rawQuery("VACUUM INTO ?", arrayOf(dbSnapshot.absolutePath)).use { it.moveToFirst() }
            } else {
                sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
                dbFile.copyTo(dbSnapshot, overwrite = true)
            }
        } finally {
            sqlite.close()
        }
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("db.sqlite"))
            zip.write(dbSnapshot.readBytes())
            zip.closeEntry()
            dir.listFiles()?.forEach { f ->
                val skip = !f.isFile || f.name == "db.sqlite" || f.name == "db.sqlite-wal" ||
                    f.name == "db.sqlite-shm" || f.name.endsWith(".log") || f.name.endsWith(".lock")
                if (!skip) {
                    zip.putNextEntry(ZipEntry(f.name))
                    zip.write(f.readBytes())
                    zip.closeEntry()
                }
            }
        }
        dbSnapshot.delete()
        return baos.toByteArray()
    }

    /** Closes the wallet, replaces the datadir with the snapshot, reopens. Holds no lock itself. */
    private suspend fun installSnapshotLocked(snapshot: ByteArray, phrase: String): String {
        wallet?.close()
        wallet = null
        onchain = null
        val dir = dataDir()
        dir.listFiles()?.forEach { if (it.isFile && !it.name.endsWith(".log")) it.delete() }
        ZipInputStream(ByteArrayInputStream(snapshot)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = File(entry.name).name // flat datadir; strips any path traversal
                if (!entry.isDirectory && name.isNotBlank()) {
                    File(dir, name).writeBytes(zip.readBytes())
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        mnemonicStore.save(phrase)
        return openLocked(phrase = phrase, createIfMissing = false, skipRecovery = true)
    }

    suspend fun decryptBackupPhrase(uri: Uri, passphrase: CharArray): String = withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 26) { "Encrypted backup requires Android 8.0 or newer" }
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("could not read the selected file")
        BackupCrypto.decrypt(bytes, passphrase).toString(Charsets.UTF_8)
    }

    private suspend fun openLocked(
        phrase: String,
        createIfMissing: Boolean,
        skipRecovery: Boolean,
    ): String {
        // Never double-open: a live instance holds the datadir lock and a second
        // Wallet.open on the same datadir fails in Bark's lock manager.
        wallet?.close()
        wallet = null
        onchain = null
        val config = barkConfig()
        val dir = dataDir().absolutePath
        val onchain = OnchainWallet.default(
            network = activeNetwork,
            mnemonic = phrase,
            config = config,
            datadir = dir,
        )
        val opened = Wallet.open(
            network = activeNetwork,
            mnemonicOrSeed = phrase,
            config = config,
            args = WalletOpenArgs(
                runDaemon = false,
                datadir = dir,
                onchain = onchain,
                createIfNotExists = createIfMissing,
                createWithoutServer = true,
                skipRecovery = skipRecovery,
            ),
        )
        wallet = opened
        this.onchain = onchain
        openedFingerprint = opened.fingerprint()
        mnemonicStore.save(phrase)
        return opened.fingerprint()
    }

    private fun requireWallet(): Wallet = wallet ?: error("wallet is closed")

    private fun requireOnchain(): OnchainWallet = onchain ?: error("wallet is closed")

    private val activeNetwork: Network =
        if (BuildConfig.DEFAULT_NETWORK == "regtest") Network.REGTEST else Network.SIGNET

    private fun barkConfig(): Config {
        check(!BuildConfig.MAINNET_ENABLED) { "mainnet is compile-time disabled" }
        return Config(
            serverAddress = BuildConfig.BARK_SERVER,
            serverAccessToken = null,
            esploraAddress = BuildConfig.BARK_ESPLORA.ifBlank { null },
            bitcoindAddress = BuildConfig.BARK_BITCOIND.ifBlank { null },
            bitcoindCookiefile = null,
            bitcoindUser = BuildConfig.BARK_BITCOIND_USER.ifBlank { null },
            bitcoindPass = BuildConfig.BARK_BITCOIND_PASS.ifBlank { null },
            vtxoRefreshExpiryThreshold = null,
            vtxoExitMargin = null,
            htlcRecvClaimDelta = null,
            fallbackFeeRate = null,
            roundTxRequiredConfirmations = null,
            daemonSyncIntervalSecs = null,
            offboardRequiredConfirmations = null,
            daemonManualSync = true,
            lightningReceiveClaimRetries = null,
            userAgent = "keel/${BuildConfig.VERSION_NAME}",
        )
    }
}
