package io.clarionchain.keel.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.clarionchain.keel.core.ArkSendPhase
import io.clarionchain.keel.BuildConfig
import io.clarionchain.keel.core.BalanceBreakdown
import io.clarionchain.keel.core.BitcoinNetwork
import io.clarionchain.keel.core.PaymentKind
import io.clarionchain.keel.core.PaymentRequestParser
import io.clarionchain.keel.core.Sats
import io.clarionchain.keel.core.SendError
import io.clarionchain.keel.core.VtxoExpiry
import io.clarionchain.keel.data.DisplayPrice
import io.clarionchain.keel.data.PriceRepository
import io.clarionchain.keel.data.WalletRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uniffi.bark.ExitVtxo
import uniffi.bark.LightningSendStatus
import uniffi.bark.Movement
import java.time.Instant

enum class Screen {
    LOADING,
    LOCKED,
    WELCOME,
    SHOW_PHRASE,
    VERIFY_PHRASE,
    RESTORE,
    HOME,
    RECEIVE,
    SEND,
    SCAN_QR,
    SETTINGS,
    GET_TEST_COINS,
    REVEAL_PHRASE,
    EXIT,
}

enum class ReceiveMode {
    ARK,
    LIGHTNING,
    ONCHAIN,
}

data class KeelUiState(
    val screen: Screen = Screen.LOADING,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val pendingPhraseWords: List<String> = emptyList(),
    val verifyIndexes: List<Int> = emptyList(),
    val fingerprint: String? = null,
    val balance: BalanceBreakdown? = null,
    val lastSyncEpochMs: Long? = null,
    val history: List<Movement> = emptyList(),
    val receiveAddress: String? = null,
    val receiveMode: ReceiveMode = ReceiveMode.ARK,
    val receiveAmount: String = "",
    val lightningInvoice: String? = null,
    val lightningPaid: Boolean = false,
    val boardAddress: String? = null,
    val hasPendingExits: Boolean = false,
    val exitVtxos: List<ExitVtxo> = emptyList(),
    val sendInput: String = "",
    val sendAmount: String = "",
    val sendKind: PaymentKind? = null,
    val sendVtxoIds: List<String>? = null,
    val sendPhase: ArkSendPhase = ArkSendPhase.IDLE,
    val sendFee: Sats? = null,
    val sendTotal: Sats? = null,
    val fiatCurrency: String = "USD",
    val price: DisplayPrice? = null,
    val revealWords: List<String> = emptyList(),
    val lastAutoBackupEpochMs: Long? = null,
    /** Wallet.open still running after the Home screen is already visible. */
    val opening: Boolean = false,
)

class KeelViewModel(application: Application) : AndroidViewModel(application) {
    private val wallet = WalletRepository(application)
    private val prices = PriceRepository()
    private val activeNetwork: BitcoinNetwork =
        if (BuildConfig.DEFAULT_NETWORK == "regtest") BitcoinNetwork.REGTEST else BitcoinNetwork.SIGNET
    private val _state = MutableStateFlow(KeelUiState())
    val state: StateFlow<KeelUiState> = _state

    init {
        viewModelScope.launch { boot() }
    }

    private suspend fun boot() {
        runCatching {
            if (wallet.hasStoredWallet()) {
                // App lock: never open the wallet before the user authenticates.
                _state.update { it.copy(screen = Screen.LOCKED) }
            } else {
                _state.update { it.copy(screen = Screen.WELCOME) }
            }
        }.onFailure { err ->
            _state.update { it.copy(screen = Screen.WELCOME, error = safeMessage(err)) }
        }
        refreshPrice()
    }

    /** Called after the device-credential/biometric prompt succeeds on the lock screen. */
    fun unlock() {
        if (_state.value.screen != Screen.LOCKED || _state.value.busy || _state.value.opening) return
        // UI-only lock: wallet still open in memory — just reveal it.
        if (wallet.isOpen()) {
            _state.update {
                it.copy(screen = Screen.HOME, error = null, fingerprint = wallet.currentFingerprint(), opening = false)
            }
            viewModelScope.launch { refreshHome(quiet = true) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(screen = Screen.HOME, opening = true, busy = false, error = null) }
            runCatching { wallet.openExisting() }
                .onSuccess { fp ->
                    _state.update { it.copy(opening = false, fingerprint = fp) }
                    runCatching {
                        Triple(wallet.balance(), wallet.history(), wallet.hasPendingExits())
                    }.onSuccess { (balance, history, pending) ->
                        _state.update {
                            it.copy(balance = balance, history = history, hasPendingExits = pending)
                        }
                    }
                    refreshHome(quiet = true)
                }
                .onFailure { err ->
                    _state.update {
                        it.copy(opening = false, busy = false, screen = Screen.LOCKED, error = safeMessage(err))
                    }
                }
        }
    }

    /** UI gate when the app goes to the background. The wallet stays open in memory. */
    fun lock() {
        if (!wallet.hasStoredWallet()) return
        val s = _state.value
        if (s.screen == Screen.WELCOME || s.screen == Screen.LOADING || s.screen == Screen.LOCKED) return
        _state.update {
            KeelUiState(screen = Screen.LOCKED, fiatCurrency = it.fiatCurrency, price = it.price)
        }
    }

    private var backgroundedAtMs: Long? = null

    fun onBackground() {
        if (backgroundedAtMs == null) backgroundedAtMs = System.currentTimeMillis()
    }

    fun onForeground() {
        val at = backgroundedAtMs ?: return
        backgroundedAtMs = null
        // Short switches (app switcher, share sheet) stay unlocked; real absence locks.
        if (System.currentTimeMillis() - at > 60_000) lock()
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    fun exportEncryptedBackup(uri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            runCatching { wallet.exportEncryptedBackup(uri, passphrase) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            busy = false,
                            notice = "Encrypted backup saved. Keel does not store your passphrase \u2014 without it the file cannot be opened.",
                        )
                    }
                }
                .onFailure { err -> _state.update { it.copy(busy = false, error = safeMessage(err)) } }
            passphrase.fill('\u0000')
        }
    }

    fun exportFullBackup(uri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            runCatching { wallet.exportFullBackup(uri, passphrase) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            busy = false,
                            notice = "Full wallet backup saved (phrase + state). Keel does not store your passphrase.",
                        )
                    }
                }
                .onFailure { err -> _state.update { it.copy(busy = false, error = safeMessage(err)) } }
            passphrase.fill(' ')
        }
    }

    /** Full-backup restore (KEELDB01): phrase + wallet state, no server needed. */
    fun importFullBackup(uri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            runCatching { wallet.importFullBackup(uri, passphrase) }
                .onSuccess { fp ->
                    _state.update {
                        it.copy(
                            busy = false,
                            fingerprint = fp,
                            screen = Screen.HOME,
                            notice = "Wallet fully restored from backup.",
                        )
                    }
                    refreshHome()
                }
                .onFailure { err -> _state.update { it.copy(busy = false, error = safeMessage(err)) } }
            passphrase.fill(' ')
        }
    }

    fun hasAutoBackup(): Boolean = wallet.hasAutoBackup()

    fun restoreFromAutoBackup() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            runCatching { wallet.restoreFromAutoBackup() }
                .onSuccess { fp ->
                    _state.update {
                        it.copy(
                            busy = false,
                            fingerprint = fp,
                            screen = Screen.HOME,
                            notice = "Restored the latest local auto-backup.",
                        )
                    }
                    refreshHome()
                }
                .onFailure { err -> _state.update { it.copy(busy = false, error = safeMessage(err)) } }
        }
    }

    fun restoreFromBackup(uri: Uri, passphrase: CharArray) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            runCatching {
                val phrase = wallet.decryptBackupPhrase(uri, passphrase)
                if (!wallet.isValidPhrase(phrase)) error("Backup decrypted but did not contain a valid recovery phrase")
                wallet.createFromPhrase(phrase, recoverFromServer = true)
            }.onSuccess { fp ->
                _state.update {
                    it.copy(
                        busy = false,
                        fingerprint = fp,
                        screen = Screen.HOME,
                        notice = "Wallet restored from encrypted backup.",
                    )
                }
                refreshHome()
            }.onFailure { err ->
                _state.update { it.copy(busy = false, error = safeMessage(err)) }
            }
            passphrase.fill('\u0000')
        }
    }

    fun goWelcome() = _state.update { it.copy(screen = Screen.WELCOME, pendingPhraseWords = emptyList(), revealWords = emptyList(), notice = null) }

    fun startCreate() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                val phrase = wallet.generatePhrase()
                val words = phrase.split(" ").filter { it.isNotBlank() }
                val indexes = words.indices.shuffled().take(2).sorted()
                _state.update {
                    it.copy(
                        busy = false,
                        pendingPhraseWords = words,
                        verifyIndexes = indexes,
                        screen = Screen.SHOW_PHRASE,
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(busy = false, error = safeMessage(err)) }
            }
        }
    }

    fun phraseVerified(answers: Map<Int, String>) {
        val words = _state.value.pendingPhraseWords
        val ok = _state.value.verifyIndexes.all { index ->
            answers[index]?.trim()?.equals(words.getOrNull(index), ignoreCase = true) == true
        }
        if (!ok) {
            _state.update { it.copy(error = "Those words do not match. The recovery phrase was not stored.") }
            return
        }
        createWalletFromPendingPhrase()
    }

    fun skipVerificationAndCreate() = createWalletFromPendingPhrase()

    private fun createWalletFromPendingPhrase() {
        val words = _state.value.pendingPhraseWords
        if (words.isEmpty()) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    screen = Screen.HOME,
                    opening = true,
                    busy = false,
                    error = null,
                    pendingPhraseWords = emptyList(),
                )
            }
            runCatching {
                wallet.createFromPhrase(words.joinToString(" "), recoverFromServer = false)
            }.onSuccess { fp ->
                _state.update { it.copy(opening = false, fingerprint = fp) }
                refreshHome(quiet = true)
            }.onFailure { err ->
                _state.update {
                    it.copy(opening = false, busy = false, screen = Screen.WELCOME, error = safeMessage(err))
                }
            }
        }
    }

    fun openRestore() = _state.update { it.copy(screen = Screen.RESTORE, error = null) }

    fun restore(phrase: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                if (!wallet.isValidPhrase(phrase)) error("That recovery phrase is not valid BIP39")
                _state.update { it.copy(screen = Screen.HOME, opening = true, busy = false) }
                val fp = wallet.createFromPhrase(phrase, recoverFromServer = true)
                _state.update { it.copy(opening = false, fingerprint = fp) }
                refreshHome(quiet = true)
            }.onFailure { err ->
                _state.update { it.copy(busy = false, opening = false, screen = Screen.RESTORE, error = safeMessage(err)) }
            }
        }
    }

    fun go(screen: Screen) = _state.update {
        val base = it.copy(screen = screen, error = null, revealWords = emptyList(), notice = null)
        // Entering Send after a finished/failed flow starts a clean form.
        if (screen == Screen.SEND &&
            (it.sendPhase == ArkSendPhase.SUCCEEDED || it.sendPhase == ArkSendPhase.FAILED_RECOVERY)
        ) {
            base.copy(
                sendInput = "",
                sendAmount = "",
                sendKind = null,
                sendVtxoIds = null,
                sendFee = null,
                sendTotal = null,
                sendPhase = ArkSendPhase.IDLE,
            )
        } else {
            base
        }
    }

    fun syncNow() {
        viewModelScope.launch { refreshHome() }
    }

    fun loadReceive() {
        if (_state.value.opening) return;
        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    error = null,
                    screen = Screen.RECEIVE,
                    receiveMode = ReceiveMode.ARK,
                    lightningInvoice = null,
                    lightningPaid = false,
                )
            }
            runCatching { wallet.newArkAddress() }
                .onSuccess { addr -> _state.update { it.copy(busy = false, receiveAddress = addr) } }
                .onFailure { err -> _state.update { it.copy(busy = false, error = safeMessage(err)) } }
        }
    }

    fun setReceiveMode(mode: ReceiveMode) {
        _state.update { it.copy(receiveMode = mode, lightningInvoice = null, lightningPaid = false, error = null) }
        if (mode == ReceiveMode.ONCHAIN && _state.value.boardAddress == null) {
            viewModelScope.launch {
                runCatching { wallet.boardFundingAddress() }
                    .onSuccess { addr -> _state.update { it.copy(boardAddress = addr) } }
                    .onFailure { err -> _state.update { it.copy(error = safeMessage(err)) } }
            }
        }
    }

    fun setReceiveAmount(value: String) = _state.update { it.copy(receiveAmount = value) }

    fun createLightningInvoice() {
        viewModelScope.launch {
            val amount = runCatching { Sats.parse(_state.value.receiveAmount) }.getOrElse {
                _state.update { it.copy(error = "Enter amount in whole sats") }
                return@launch
            }
            _state.update { it.copy(busy = true, error = null) }
            runCatching { wallet.createLightningInvoice(amount, "Keel receive") }
                .onSuccess { invoice ->
                    _state.update { it.copy(busy = false, lightningInvoice = invoice.invoice, lightningPaid = false) }
                    pollLightningInvoice(invoice.paymentHash)
                }
                .onFailure { err -> _state.update { it.copy(busy = false, error = safeMessage(err)) } }
        }
    }

    private fun pollLightningInvoice(paymentHash: String) {
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                val s = _state.value
                if (s.screen != Screen.RECEIVE || s.lightningInvoice == null || s.lightningPaid) return@launch
                val paid = runCatching { wallet.isInvoicePaid(paymentHash) }.getOrDefault(false)
                if (paid) {
                    runCatching { wallet.claimAllLightningReceives() }
                    _state.update { it.copy(lightningPaid = true) }
                    refreshHome()
                    return@launch
                }
            }
        }
    }

    fun openScanner() = _state.update { it.copy(screen = Screen.SCAN_QR, error = null) }

    fun onScanned(text: String) {
        _state.update { it.copy(sendInput = text.trim(), screen = Screen.SEND, sendPhase = ArkSendPhase.IDLE, error = null) }
    }

    fun boardAll() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { wallet.boardAll() }
                .onSuccess { board ->
                    walletDirty = true
                    _state.update {
                        it.copy(busy = false, notice = "Boarding ${board.amountSats} sats into Ark - settles in the next round")
                    }
                    refreshHome()
                }
                .onFailure { err -> _state.update { it.copy(busy = false, error = safeMessage(err)) } }
        }
    }

    fun loadExit() {
        _state.update { it.copy(screen = Screen.EXIT, error = null) }
        refreshExits()
    }

    fun refreshExits() {
        viewModelScope.launch {
            runCatching {
                wallet.syncExitsAndBoards()
                wallet.exitVtxos() to wallet.hasPendingExits()
            }.onSuccess { (vtxos, pending) ->
                _state.update { it.copy(exitVtxos = vtxos, hasPendingExits = pending) }
            }.onFailure { err -> _state.update { it.copy(error = safeMessage(err)) } }
        }
    }

    /** Manual claim for exits whose timelock has passed (auto-claim also runs at every sync). */
    fun claimExits() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching {
                wallet.progressExits()
                wallet.claimExits()
            }.onSuccess { result ->
                if (result != null) walletDirty = true
                _state.update {
                    it.copy(
                        busy = false,
                        notice = if (result != null) {
                            "Exit complete — your sats are back on-chain"
                        } else {
                            "No exits are ready to claim yet"
                        },
                    )
                }
                refreshExits()
                refreshHome()
            }.onFailure { err -> _state.update { it.copy(busy = false, error = safeMessage(err)) } }
        }
    }

    fun startExit() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { wallet.startExitForEntireWallet() }
                .onSuccess {
                    walletDirty = true
                    _state.update { it.copy(busy = false, notice = "Emergency exit started") }
                    refreshExits()
                }
                .onFailure { err -> _state.update { it.copy(busy = false, error = safeMessage(err)) } }
        }
    }

    fun loadGetTestCoins() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, screen = Screen.GET_TEST_COINS) }
            runCatching { wallet.newArkAddress() }
                .onSuccess { addr -> _state.update { it.copy(busy = false, receiveAddress = addr) } }
                .onFailure { err -> _state.update { it.copy(busy = false, error = safeMessage(err)) } }
        }
    }

    fun setSendInput(value: String) = _state.update { it.copy(sendInput = value, sendPhase = ArkSendPhase.IDLE, error = null) }

    fun resetSend() = _state.update {
        it.copy(
            sendInput = "",
            sendAmount = "",
            sendKind = null,
            sendVtxoIds = null,
            sendFee = null,
            sendTotal = null,
            sendPhase = ArkSendPhase.IDLE,
            error = null,
        )
    }

    fun setSendAmount(value: String) = _state.update { it.copy(sendAmount = value) }

    fun setFiatCurrency(code: String) {
        _state.update { it.copy(fiatCurrency = code) }
        refreshPrice()
    }

    fun prepareSend() {
        viewModelScope.launch {
            val parsed = PaymentRequestParser.parse(_state.value.sendInput, activeNetwork)
            if (parsed.kind == PaymentKind.UNSUPPORTED || parsed.kind == PaymentKind.EMPTY) {
                _state.update { it.copy(error = parsed.reason ?: "Could not parse destination", sendPhase = ArkSendPhase.INVALID) }
                return@launch
            }
            if (parsed.kind != PaymentKind.ARK_ADDRESS &&
                parsed.kind != PaymentKind.LIGHTNING_INVOICE &&
                parsed.kind != PaymentKind.ONCHAIN_ADDRESS
            ) {
                _state.update { it.copy(error = "Ark address, Lightning invoice, or Signet Bitcoin address only") }
                return@launch
            }
            val amount = parsed.amountSats?.let { Sats(it) }
                ?: runCatching { Sats.parse(_state.value.sendAmount) }.getOrElse {
                    _state.update { it.copy(error = "Enter amount in whole sats") }
                    return@launch
                }
            _state.update { it.copy(busy = true, sendPhase = ArkSendPhase.QUOTING, error = null) }
            runCatching {
                // Expired VTXOs can never be spent; detect before even quoting. If the
                // healthy remainder cannot cover the amount, only on-chain recovery helps.
                val expiry = runCatching { wallet.expiryReport() }.getOrNull()
                if (expiry != null && expiry.needsRecovery && expiry.okSats < amount.value) {
                    throw ExpiredFundsException(expiry.expiredSats)
                }
                when (parsed.kind) {
                    PaymentKind.ARK_ADDRESS -> {
                        if (!wallet.validateArkAddress(parsed.original)) {
                            error("This Ark address isn't payable from Keel (wrong server or unsupported). Both wallets must use the same Ark server (ark.signet.2nd.dev).")
                        }
                        Triple(amount, wallet.estimateArkFee(amount), null)
                    }
                    PaymentKind.LIGHTNING_INVOICE -> Triple(amount, wallet.estimateLightningSendFee(amount), null)
                    PaymentKind.ONCHAIN_ADDRESS -> {
                        // Offboard: pick the smallest sufficient set of VTXOs (largest first).
                        // Expired VTXOs are never selected.
                        val expiredIds = expiry?.expired?.map { it.id }?.toSet() ?: emptySet()
                        val spendable = wallet.spendableVtxos()
                            .filter { it.id !in expiredIds }
                            .sortedByDescending { it.amountSats }
                        val selected = mutableListOf<String>()
                        var covered = 0L
                        for (vtxo in spendable) {
                            if (covered >= amount.value) break
                            selected += vtxo.id
                            covered += vtxo.amountSats.toLong()
                        }
                        if (selected.isEmpty()) error("No spendable Ark balance to offboard")
                        val fee = wallet.estimateOffboardFee(parsed.original, selected)
                        while (covered < amount.value + fee.value && selected.size < spendable.size) {
                            val next = spendable.first { it.id !in selected }
                            selected += next.id
                            covered += next.amountSats.toLong()
                        }
                        if (covered < amount.value + fee.value) error("Not enough Ark balance for amount plus offboard fee")
                        Triple(amount, fee, selected.toList())
                    }
                    else -> error("unsupported")
                }
            }.onSuccess { (amount, fee, vtxoIds) ->
                _state.update {
                    it.copy(
                        busy = false,
                        sendKind = parsed.kind,
                        sendAmount = amount.value.toString(),
                        sendFee = fee,
                        sendTotal = amount + fee,
                        sendVtxoIds = vtxoIds,
                        sendPhase = ArkSendPhase.CONFIRM,
                    )
                }
            }.onFailure { err ->
                if (err is ExpiredFundsException) {
                    _state.update {
                        it.copy(
                            busy = false,
                            sendPhase = ArkSendPhase.FAILED_RECOVERY,
                            error = err.message,
                        )
                    }
                } else {
                    _state.update { it.copy(busy = false, sendPhase = ArkSendPhase.INVALID, error = safeMessage(err)) }
                }
            }
        }
    }

    fun submitSend() {
        viewModelScope.launch {
            val parsed = PaymentRequestParser.parse(_state.value.sendInput, activeNetwork)
            val amount = runCatching { Sats.parse(_state.value.sendAmount) }.getOrNull() ?: return@launch
            val startedAt = System.currentTimeMillis() - 5_000
            _state.update { it.copy(busy = true, sendPhase = ArkSendPhase.SUBMITTING, error = null) }
            runCatching {
                when (parsed.kind) {
                    PaymentKind.LIGHTNING_INVOICE -> {
                        val status = withTimeoutOrNull(60_000) {
                            wallet.payLightningInvoice(parsed.original, amount)
                        }
                        when (status) {
                            is LightningSendStatus.Paid -> Unit
                            null -> error("Timed out waiting for the Lightning payment")
                            else -> error("Lightning payment did not settle")
                        }
                    }
                    PaymentKind.ONCHAIN_ADDRESS -> {
                        val ids = _state.value.sendVtxoIds ?: error("No VTXOs selected - review the payment again")
                        wallet.offboardVtxos(ids, parsed.original)
                    }
                    else -> wallet.sendArk(parsed.original, amount)
                }
            }
                .onSuccess {
                    walletDirty = true
                    _state.update { it.copy(busy = false, sendPhase = ArkSendPhase.SUCCEEDED) }
                    refreshHome()
                }
                .onFailure { err ->
                    if (VtxoExpiry.isExpiredVtxoRejection(err.message)) {
                        _state.update {
                            it.copy(
                                busy = false,
                                sendPhase = ArkSendPhase.FAILED_RECOVERY,
                                error = ExpiredFundsException().message,
                            )
                        }
                        refreshHome()
                    } else {
                        finishSendAfterError(amount.value, startedAt, safeMessage(err))
                    }
                }
        }
    }

    fun checkPendingSend() {
        viewModelScope.launch {
            val amount = runCatching { Sats.parse(_state.value.sendAmount) }.getOrNull()?.value ?: return@launch
            _state.update { it.copy(busy = true) }
            refreshHome(quiet = true)
            if (historyHasRecentOutbound(amount, System.currentTimeMillis() - 10 * 60_000)) {
                _state.update { it.copy(busy = false, sendPhase = ArkSendPhase.SUCCEEDED, error = null) }
            } else {
                _state.update {
                    it.copy(
                        busy = false,
                        sendPhase = ArkSendPhase.RECONCILING,
                        error = "Not confirmed yet. Sync to check — retry only if your spendable balance did not drop.",
                    )
                }
            }
        }
    }

    private suspend fun finishSendAfterError(amountSats: Long, startedAt: Long, raw: String) {
        _state.update { it.copy(sendPhase = ArkSendPhase.RECONCILING, error = null) }
        refreshHome(quiet = true)
        if (historyHasRecentOutbound(amountSats, startedAt)) {
            _state.update { it.copy(busy = false, sendPhase = ArkSendPhase.SUCCEEDED, error = null) }
            return
        }
        if (SendError.isUncertain(raw)) {
            _state.update {
                it.copy(
                    busy = false,
                    sendPhase = ArkSendPhase.RECONCILING,
                    error = "Not confirmed yet. Sync to check — retry only if your spendable balance did not drop.",
                )
            }
        } else {
            _state.update {
                it.copy(
                    busy = false,
                    sendPhase = ArkSendPhase.FAILED_RETRYABLE,
                    error = SendError.friendly(raw),
                )
            }
        }
    }

    private fun historyHasRecentOutbound(amountSats: Long, sinceEpochMs: Long): Boolean {
        val movements = _state.value.history.map {
            SendError.OutboundMovement(
                kind = it.subsystemKind,
                effectiveBalanceSats = it.effectiveBalanceSats.toLong(),
                createdAtEpochMs = parseCreatedAtEpochMs(it.createdAt),
            )
        }
        return SendError.recentOutboundFound(movements, amountSats, sinceEpochMs)
    }

    private fun parseCreatedAtEpochMs(raw: String): Long =
        runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    fun refreshDueVtxos() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            runCatching { wallet.refreshDueVtxos() }
                .onSuccess { txid ->
                    _state.update {
                        it.copy(
                            busy = false,
                            notice = if (txid != null) {
                                "Refreshing expiring funds in the next Ark round"
                            } else {
                                "No funds need a refresh right now"
                            },
                        )
                    }
                    refreshHome()
                }
                .onFailure { err -> _state.update { it.copy(busy = false, error = safeMessage(err)) } }
        }
    }

    fun revealPhrase(authenticated: Boolean) {
        if (!authenticated) return
        val store = io.clarionchain.keel.data.MnemonicStore(getApplication())
        val words = store.load()?.split(" ").orEmpty()
        _state.update { it.copy(screen = Screen.REVEAL_PHRASE, revealWords = words) }
    }

    fun deleteWalletConfirmed() {
        viewModelScope.launch {
            runCatching { wallet.deleteWallet() }
                .onSuccess {
                    _state.value = KeelUiState(screen = Screen.WELCOME)
                }
                .onFailure { err -> _state.update { it.copy(error = safeMessage(err)) } }
        }
    }

    private var walletDirty = false

    private suspend fun refreshHome(quiet: Boolean = false) {
        if (!quiet) _state.update { it.copy(busy = true) }
        // Sync is best-effort: a failing sync (e.g. the server forgot a stale
        // pending board and answers "unknown payment") must not block balance
        // and history, which read local state. A later successful sync clears
        // the banner.
        val syncError = runCatching { wallet.sync() }.exceptionOrNull()?.let { safeMessage(it) }
        var exitClaimed = false
        runCatching {
            val claimed = runCatching { wallet.claimAllLightningReceives() }.getOrDefault(0)
            if (claimed > 0) walletDirty = true
            wallet.syncExitsAndBoards()
            // Exit progression + claiming talk to the chain only (no Ark
            // server): they must keep working even when the server sync is
            // broken, or an exit could never complete.
            runCatching { wallet.progressExits() }
            exitClaimed = runCatching { wallet.claimExits() }.getOrNull() != null
            if (exitClaimed) walletDirty = true
            // On-chain wallet: claimed exits land here.
            runCatching { wallet.syncOnchain() }
            // Permanent expiry protection (0.5.4): sign delegated "renewal
            // appointments" for every VTXO — the server refreshes them even
            // while the app is closed.
            val (refreshedNow, _) = runCatching { wallet.scheduleRefreshes() }.getOrDefault(0 to 0)
            if (refreshedNow > 0) walletDirty = true
            val pendingExits = runCatching { wallet.hasPendingExits() }.getOrDefault(false)
            Triple(wallet.balance(), wallet.history(), pendingExits) to refreshedNow
        }.onSuccess { (snapshot, refreshedNow) ->
            val (balance, history, pendingExits) = snapshot
            _state.update {
                it.copy(
                    busy = false,
                    error = syncError,
                    balance = balance,
                    history = history,
                    hasPendingExits = pendingExits,
                    lastSyncEpochMs = System.currentTimeMillis(),
                    notice = when {
                        exitClaimed -> "Exit complete — your sats are back on-chain"
                        refreshedNow > 0 -> "Funds nearing expiry are being refreshed by the Ark server"
                        else -> it.notice
                    },
                )
            }
            // Continuous encrypted backup: snapshot after mutating operations.
            if (walletDirty) {
                walletDirty = false
                runCatching { wallet.autoBackupNow() }
                    .onSuccess { gen ->
                        if (gen != null) {
                            _state.update { it.copy(lastAutoBackupEpochMs = System.currentTimeMillis()) }
                        }
                    }
            }
        }.onFailure { err ->
            _state.update { it.copy(busy = false, error = syncError ?: safeMessage(err)) }
        }
        refreshPrice()
    }

    private fun refreshPrice() {
        val code = _state.value.fiatCurrency
        viewModelScope.launch {
            runCatching { prices.fetch(code) }
                .onSuccess { quote -> _state.update { it.copy(price = quote) } }
        }
    }

    private fun safeMessage(err: Throwable): String {
        val raw = err.message ?: err::class.simpleName ?: "unknown error"
        val lowered = raw.lowercase()
        if (listOf("abandon", "mnemonic", "seed", "preimage").any { it in lowered } && raw.split(" ").size >= 8) {
            return "A wallet error occurred (details redacted)"
        }
        // Second's server forgot a stale pending board/payment (its 2026-09
        // fork). Harmless to funds, but the record only clears by rebuilding
        // the wallet from the server's current view.
        if ("unknown payment" in lowered) {
            return "The Ark server lost track of an old payment record. Your funds are safe. If this stays, restore from your seed backup to resync."
        }
        return raw.take(280)
    }
}

/** Expired VTXOs can never be spent normally; the only path is on-chain recovery. */
private class ExpiredFundsException(expiredSats: Long? = null) : Exception(
    buildString {
        append("Some funds have expired and cannot be sent normally.")
        if (expiredSats != null && expiredSats > 0) append(" Expired: $expiredSats sats.")
        append(" Recover them on-chain with an emergency exit.")
    },
)
