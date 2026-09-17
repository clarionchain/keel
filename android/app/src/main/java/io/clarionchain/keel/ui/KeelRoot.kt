package io.clarionchain.keel.ui

import android.app.Activity
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.clarionchain.keel.BuildConfig
import io.clarionchain.keel.R
import io.clarionchain.keel.core.ArkSendPhase
import io.clarionchain.keel.core.PaymentKind
import io.clarionchain.keel.data.PriceRepository
import io.clarionchain.keel.data.SatsFiatLabel
import io.clarionchain.keel.data.FullBackupEnvelope
import io.clarionchain.keel.data.authenticate
import io.clarionchain.keel.data.confirmSpend
import io.clarionchain.keel.data.qrBitmap
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import uniffi.bark.ExitState
import uniffi.bark.Movement

@Composable
fun KeelRoot(vm: KeelViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Lock the UI after the app spends more than a minute in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.onForeground() }
    BackHandler(
        enabled = state.screen != Screen.HOME && state.screen != Screen.WELCOME &&
            state.screen != Screen.LOADING && state.screen != Screen.LOCKED,
    ) {
        when (state.screen) {
            Screen.SHOW_PHRASE, Screen.VERIFY_PHRASE, Screen.RESTORE -> vm.goWelcome()
            Screen.REVEAL_PHRASE -> vm.go(Screen.SETTINGS)
            Screen.SCAN_QR -> vm.go(Screen.SEND)
            Screen.EXIT -> vm.go(Screen.SETTINGS)
            else -> vm.go(Screen.HOME)
        }
    }
    Scaffold { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                SignetBadge()
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Working" })
                }
                state.error?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::dismissError) { Text("Dismiss") }
                }
                state.notice?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = vm::dismissNotice) { Text("OK") }
                }
                when (state.screen) {
                    Screen.LOADING -> { /* boot is a local file check; next frame is Welcome or Locked */ }
                    Screen.LOCKED -> Locked(vm)
                    Screen.WELCOME -> Welcome(vm)
                    Screen.SHOW_PHRASE -> ShowPhrase(state, vm)
                    Screen.VERIFY_PHRASE, Screen.RESTORE -> RestoreOrVerify(state, vm)
                    Screen.HOME -> Home(state, vm)
            Screen.RECEIVE -> Receive(state, vm)
            Screen.SEND -> Send(state, vm)
            Screen.SCAN_QR -> ScanQr(vm)
            Screen.EXIT -> Exit(state, vm)
                    Screen.SETTINGS -> Settings(state, vm)
                    Screen.GET_TEST_COINS -> GetTestCoins(state, vm)
                    Screen.REVEAL_PHRASE -> Reveal(state, vm)
                }
            }
        }
    }
}

@Composable
private fun SignetBadge() {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            BuildConfig.DEFAULT_NETWORK.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.Center).semantics { contentDescription = "Active network" },
        )
        Image(
            painterResource(R.drawable.logo),
            contentDescription = "Keel",
            modifier = Modifier.align(Alignment.CenterStart).size(56.dp).clip(RoundedCornerShape(14.dp)),
        )
    }
}

@Composable
private fun Locked(vm: KeelViewModel) {
    val view = LocalView.current
    val activity = view.context as FragmentActivity
    var prompted by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!prompted) {
            prompted = true
            authenticate(
                activity,
                title = "Unlock Keel",
                subtitle = "Authenticate to open your wallet",
                onAuthenticated = vm::unlock,
                onError = { /* user can retry with the button */ },
            )
        }
    }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Keel", style = MaterialTheme.typography.displayMedium)
        Text("Locked", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                authenticate(
                    activity,
                    title = "Unlock Keel",
                    subtitle = "Authenticate to open your wallet",
                    onAuthenticated = vm::unlock,
                    onError = { },
                )
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Unlock") }
    }
}

@Composable
private fun Welcome(vm: KeelViewModel) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Keel", style = MaterialTheme.typography.displayMedium)
        Text("Self-custodial Ark wallet", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Button(onClick = vm::startCreate, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Create wallet") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = vm::openRestore, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Restore") }
    }
}

@Composable
private fun ShowPhrase(state: KeelUiState, vm: KeelViewModel) {
    SecureWindow()
    var showSkipConfirm by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recovery phrase", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Write it down or save an encrypted backup. Screenshots are blocked.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        state.pendingPhraseWords.forEachIndexed { index, word ->
            Text("${index + 1}. $word", fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(4.dp))
        ExportBackupButton(vm, label = "Save encrypted backup", requireAuth = false)
        Button(onClick = { vm.go(Screen.VERIFY_PHRASE) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("I wrote the words down")
        }
        TextButton(onClick = { showSkipConfirm = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Skip word check")
        }
        TextButton(onClick = vm::goWelcome, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
    }
    if (showSkipConfirm) {
        AlertDialog(
            onDismissRequest = { showSkipConfirm = false },
            title = { Text("Skip verification?") },
            text = { Text("Without the words or an encrypted backup, a lost phone means losing these funds.") },
            confirmButton = {
                TextButton(onClick = {
                    showSkipConfirm = false
                    vm.skipVerificationAndCreate()
                }) { Text("Skip anyway") }
            },
            dismissButton = {
                TextButton(onClick = { showSkipConfirm = false }) { Text("Back") }
            },
        )
    }
}

@Composable
private fun RestoreOrVerify(state: KeelUiState, vm: KeelViewModel) {
    SecureWindow() // seed words visible/typed on both branches
    if (state.screen == Screen.VERIFY_PHRASE) {
        var answers = remember { mutableStateMapOf<Int, String>() }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Confirm your words", style = MaterialTheme.typography.headlineSmall)
            state.verifyIndexes.forEach { index ->
                OutlinedTextField(
                    value = answers[index] ?: "",
                    onValueChange = { answers[index] = it },
                    label = { Text("Word ${index + 1}") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(onClick = { vm.phraseVerified(answers.toMap()) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Verify and create wallet")
            }
        }
    } else {
        var phrase by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Restore", style = MaterialTheme.typography.headlineSmall)
            ImportBackupButton(vm)
            Text("or", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = phrase,
                onValueChange = { phrase = it },
                label = { Text("Recovery phrase") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vm.restore(phrase) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Restore") }
            TextButton(onClick = vm::goWelcome, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

@Composable
private fun Home(state: KeelUiState, vm: KeelViewModel) {
    val b = state.balance
    val spendable = b?.spendable?.value ?: 0L
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(48.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "SPENDABLE",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(formatSats(spendable), style = MaterialTheme.typography.displayLarge)
                Text(
                    " sats",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                SatsFiatLabel(spendable, state.price),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = vm::loadReceive,
                enabled = !state.opening,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Receive") }
            Button(
                onClick = { vm.go(Screen.SEND) },
                enabled = !state.opening,
                modifier = Modifier.weight(1f).height(52.dp),
            ) { Text("Send") }
        }
        if (state.opening) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Loading…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.hasPendingExits) {
            Spacer(Modifier.height(12.dp))
            // Subtle pulsing border: signals the exit is actively progressing.
            val exitPulse = rememberInfiniteTransition(label = "exitPulse")
            val exitPulseAlpha by exitPulse.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "exitPulseAlpha",
            )
            OutlinedButton(
                onClick = vm::loadExit,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = exitPulseAlpha)),
            ) {
                Text("Emergency exit in progress", color = MaterialTheme.colorScheme.error)
            }
        }

        if (b != null && b.expired.value > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                "${formatSats(b.expired.value)} sats expired and cannot be sent normally",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = vm::loadExit, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Recover on-chain")
            }
        }

        if (b != null && b.expiringSoon.value > 0 && b.expired.value == 0L) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "${formatSats(b.expiringSoon.value)} sats expire soon",
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = vm::refreshDueVtxos, enabled = !state.busy) { Text("Refresh now") }
            }
        }

        val empty = b != null && spendable == 0L &&
            b.pendingRound.value == 0L && b.lightningLocked.value == 0L &&
            b.boardPending.value == 0L && b.exitPending.value == 0L && b.onchain.value == 0L &&
            b.expired.value == 0L
        if (empty && BuildConfig.DEFAULT_NETWORK == "signet") {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = vm::loadGetTestCoins, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Get free test coins")
            }
        }

        val rows = mutableListOf<Pair<String, Long>>()
        if (b != null) {
            if (b.pendingRound.value > 0) rows += "Settling in Ark" to b.pendingRound.value
            if (b.lightningLocked.value > 0) rows += "In a Lightning payment" to b.lightningLocked.value
            if (b.boardPending.value > 0) rows += "Moving into Ark" to b.boardPending.value
            if (b.exitPending.value > 0) rows += "Exit in progress" to b.exitPending.value
            if (b.onchain.value > 0) rows += "On-chain" to b.onchain.value
        }
        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${formatSats(value)} sats")
                }
            }
        }
        if ((b?.onchain?.value ?: 0L) > 0) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = vm::boardAll, enabled = !state.busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Move to Ark")
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            TextButton(onClick = vm::syncNow) { Text("Sync") }
            TextButton(onClick = { vm.go(Screen.SETTINGS) }) { Text("Settings") }
        }
        Text(
            "Synced ${state.lastSyncEpochMs?.let { formatTime(it) } ?: "never"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        if (state.history.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text(
                "ACTIVITY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            state.history.sortedByDescending { it.createdAt }.take(20).forEach { movement ->
                HistoryRow(movement)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun HistoryRow(movement: Movement) {
    val amount = movement.effectiveBalanceSats
    val incoming = amount > 0
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (incoming) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
            contentDescription = if (incoming) "Received" else "Sent",
            tint = if (incoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(movementLabel(movement), style = MaterialTheme.typography.bodyMedium)
            Text(
                movementTime(movement.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "${if (incoming) "+" else "-"}${formatSats(abs(amount))}",
            style = MaterialTheme.typography.bodyMedium,
            color = if (incoming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun movementLabel(movement: Movement): String {
    val base = when (movement.subsystemKind.lowercase()) {
        "arkoor" -> "Ark payment"
        "lightning" -> "Lightning"
        "board" -> "Board"
        "offboard" -> "Offboard"
        "exit" -> "Exit"
        "round" -> "Refresh"
        else -> movement.subsystemKind.replaceFirstChar { it.uppercase() }
    }
    val status = movement.status.lowercase()
    return if (status == "finished" || status == "success" || status == "complete") base else "$base - $status"
}

private fun movementTime(createdAt: String): String =
    if (createdAt.length >= 16) "${createdAt.substring(5, 10)} ${createdAt.substring(11, 16)}" else createdAt

private fun exitStateLabel(state: ExitState): String = when (state) {
    is ExitState.Start -> "started - confirming on-chain"
    is ExitState.Processing -> "confirming on-chain"
    is ExitState.AwaitingDelta -> "waiting on timelock (~1 day)"
    is ExitState.Claimable -> "ready to claim on-chain"
    is ExitState.ClaimInProgress -> "claiming..."
    is ExitState.Claimed -> "done - funds on-chain"
    is ExitState.VtxoAlreadySpent -> "already spent"
    is ExitState.Canceled -> "canceled"
}

@Composable
private fun Receive(state: KeelUiState, vm: KeelViewModel) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Receive", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReceiveMode.entries.forEach { mode ->
                val label = when (mode) {
                    ReceiveMode.ARK -> "Ark"
                    ReceiveMode.LIGHTNING -> "Lightning"
                    ReceiveMode.ONCHAIN -> "On-chain"
                }
                if (state.receiveMode == mode) {
                    Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(label) }
                } else {
                    OutlinedButton(onClick = { vm.setReceiveMode(mode) }, modifier = Modifier.weight(1f)) { Text(label) }
                }
            }
        }
        if (state.receiveMode == ReceiveMode.ONCHAIN) {
            state.boardAddress?.let { address ->
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = remember(address) { qrBitmap(address) },
                    contentDescription = "QR code for Bitcoin funding address",
                    modifier = Modifier.height(240.dp).fillMaxWidth(),
                )
                Text(
                    shortAddress(address),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(address)) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Copy address") }
                Text(
                    "Send Signet Bitcoin here, then tap Move to Ark on Home.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } ?: Text(
                "Loading funding address...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (state.receiveMode == ReceiveMode.ARK) {
            state.receiveAddress?.let { address ->
                Spacer(Modifier.height(8.dp))
                Image(
                    bitmap = remember(address) { qrBitmap(address) },
                    contentDescription = "QR code for Ark address",
                    modifier = Modifier.height(240.dp).fillMaxWidth(),
                )
                Text(
                    shortAddress(address),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(address)) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Copy address") }
            }
        } else if (state.lightningPaid) {
            Spacer(Modifier.height(24.dp))
            Text(
                "Paid",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Button(onClick = { vm.go(Screen.HOME) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
        } else if (state.lightningInvoice == null) {
            OutlinedTextField(
                value = state.receiveAmount,
                onValueChange = vm::setReceiveAmount,
                label = { Text("Amount (sats)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = vm::createLightningInvoice,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Create invoice") }
        } else {
            val invoice = state.lightningInvoice
            Spacer(Modifier.height(8.dp))
            Image(
                bitmap = remember(invoice) { qrBitmap(invoice) },
                contentDescription = "QR code for Lightning invoice",
                modifier = Modifier.height(240.dp).fillMaxWidth(),
            )
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(invoice)) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Copy invoice") }
            Text(
                "Waiting for payment...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (BuildConfig.DEFAULT_NETWORK == "signet") {
            TextButton(onClick = vm::loadGetTestCoins) { Text("Need test coins?") }
        }
        TextButton(onClick = { vm.go(Screen.HOME) }) { Text("Back") }
    }
}

@Composable
private fun GetTestCoins(state: KeelUiState, vm: KeelViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val address = state.receiveAddress
    val arrived = state.balance?.spendable?.value ?: 0L
    // Returning from the faucet sheet resumes this screen - check for coins automatically.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.syncNow() }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Get test coins", style = MaterialTheme.typography.headlineSmall)
        if (arrived > 0) {
            Spacer(Modifier.height(16.dp))
            Text(
                "${formatSats(arrived)} sats arrived",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Button(onClick = { vm.go(Screen.HOME) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Done")
            }
        } else {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    address?.let {
                        clipboard.setText(AnnotatedString(it))
                        CustomTabsIntent.Builder().build()
                            .launchUrl(context, Uri.parse("https://signet.2nd.dev/"))
                    }
                },
                enabled = address != null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Copy address and open faucet") }
            Text(
                "Paste address, pick an amount, sign in with GitHub (faucet rule). Coins show up here automatically.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = vm::syncNow, modifier = Modifier.fillMaxWidth()) { Text("Check for coins") }
        }
        TextButton(onClick = { vm.go(Screen.HOME) }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun Send(state: KeelUiState, vm: KeelViewModel) {
    val view = LocalView.current
    val activity = view.context as FragmentActivity
    if (state.sendPhase == ArkSendPhase.SUCCEEDED) {
        SendSuccess(state, vm)
        return
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Send", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.sendInput,
                onValueChange = vm::setSendInput,
                label = { Text("Ark address or Lightning invoice") },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = vm::openScanner) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR code")
            }
        }
        OutlinedTextField(
            value = state.sendAmount,
            onValueChange = vm::setSendAmount,
            label = { Text("Amount (sats)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            SatsFiatLabel(state.sendAmount.toLongOrNull() ?: 0L, state.price),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val hideReview = state.sendPhase in setOf(
            ArkSendPhase.CONFIRM,
            ArkSendPhase.SUBMITTING,
            ArkSendPhase.RECONCILING,
            ArkSendPhase.FAILED_RETRYABLE,
            ArkSendPhase.SUCCEEDED,
            ArkSendPhase.FAILED_RECOVERY,
            ArkSendPhase.AUTHENTICATING,
            ArkSendPhase.QUOTING,
        )
        if (!hideReview) {
            Button(onClick = vm::prepareSend, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Review") }
        }
        if (state.sendPhase == ArkSendPhase.CONFIRM) {
            Spacer(Modifier.height(8.dp))
            ConfirmRow(
                "Type",
                when (state.sendKind) {
                    PaymentKind.LIGHTNING_INVOICE -> "Lightning"
                    PaymentKind.ONCHAIN_ADDRESS -> "On-chain (offboard)"
                    else -> "Ark"
                },
            )
            ConfirmRow("Amount", "${state.sendAmount} sats")
            ConfirmRow("Fee", "${state.sendFee?.value ?: "-"} sats")
            ConfirmRow("Total", "${state.sendTotal?.value ?: "-"} sats")
            ConfirmRow("Network", BuildConfig.DEFAULT_NETWORK.replaceFirstChar { it.uppercase() })
            Text(
                "Cannot be reversed",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    confirmSpend(activity, onAuthenticated = vm::submitSend, onError = { /* system sheet shows it */ })
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Confirm and send") }
        }
        if (state.sendPhase == ArkSendPhase.RECONCILING) {
            Button(
                onClick = vm::checkPendingSend,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Sync") }
        }
        if (state.sendPhase == ArkSendPhase.FAILED_RETRYABLE) {
            Button(onClick = vm::prepareSend, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Retry") }
            TextButton(onClick = vm::resetSend, modifier = Modifier.fillMaxWidth()) { Text("Start over") }
        }
        if (state.sendPhase == ArkSendPhase.FAILED_RECOVERY) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Cannot send",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = vm::loadExit, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Recover on-chain")
            }
            TextButton(onClick = vm::resetSend, modifier = Modifier.fillMaxWidth()) { Text("Start over") }
        }
        TextButton(onClick = { vm.go(Screen.HOME) }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun SendSuccess(state: KeelUiState, vm: KeelViewModel) {
    Column(
        Modifier.fillMaxSize().padding(bottom = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = "Payment sent",
            tint = Color(0xFF22C55E),
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Sent", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        val amount = state.sendAmount.toLongOrNull()
        if (amount != null && amount > 0) {
            Text("${formatSats(amount)} sats", style = MaterialTheme.typography.displaySmall)
            Text(
                SatsFiatLabel(amount, state.price),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        state.sendFee?.let { fee ->
            Spacer(Modifier.height(4.dp))
            Text(
                "Fee ${formatSats(fee.value)} sats",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = {
                vm.resetSend()
                vm.go(Screen.HOME)
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Done") }
    }
}

@Composable
private fun ConfirmRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun ScanQr(vm: KeelViewModel) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(android.Manifest.permission.CAMERA)
    }
    Column(Modifier.fillMaxSize()) {
        Text("Scan QR", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        if (granted) {
            val scanned = remember { mutableStateOf(false) }
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val executor = Executors.newSingleThreadExecutor()
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build()
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        val scanner = BarcodeScanning.getClient(
                            BarcodeScannerOptions.Builder()
                                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                                .build(),
                        )
                        analysis.setAnalyzer(executor) { proxy ->
                            val media = proxy.image
                            if (media == null || scanned.value) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                                .addOnSuccessListener { codes ->
                                    val value = codes.firstOrNull()?.rawValue
                                    if (value != null && !scanned.value) {
                                        scanned.value = true
                                        previewView.post { vm.onScanned(value) }
                                    }
                                }
                                .addOnCompleteListener { proxy.close() }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            ctx as LifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            Text(
                "Camera permission is needed to scan QR codes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = { vm.go(Screen.SEND) }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Settings(state: KeelUiState, vm: KeelViewModel) {
    val view = LocalView.current
    val activity = view.context as FragmentActivity
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        ConfirmRow("Network", BuildConfig.DEFAULT_NETWORK.replaceFirstChar { it.uppercase() })
        ConfirmRow("Version", BuildConfig.VERSION_NAME)
        ConfirmRow("Bark", BuildConfig.BARK_BINDING)
        ConfirmRow("Fingerprint", state.fingerprint ?: "-")
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = state.fiatCurrency,
                onValueChange = {},
                readOnly = true,
                label = { Text("Fiat currency") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                PriceRepository.CURRENCIES.forEach { code ->
                    DropdownMenuItem(
                        text = { Text(code) },
                        onClick = {
                            vm.setFiatCurrency(code)
                            expanded = false
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ExportBackupButton(vm, label = "Export encrypted backup (seed words)", requireAuth = true)
        ExportBackupButton(vm, label = "Export full wallet backup", requireAuth = true, fullBackup = true)
        Text(
            "Full backup = seed + payment history + in-progress exits. Restores without the Ark server.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val hasAutoBackup by remember(state.lastAutoBackupEpochMs) { mutableStateOf(vm.hasAutoBackup()) }
        ConfirmRow(
            "Auto-backup",
            state.lastAutoBackupEpochMs?.let { "Today ${formatTime(it)}" } ?: if (hasAutoBackup) "Saved" else "None yet",
        )
        if (hasAutoBackup) {
            var confirmRestoreAuto by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = {
                    if (confirmRestoreAuto) {
                        confirmRestoreAuto = false
                        vm.restoreFromAutoBackup()
                    } else {
                        confirmRestoreAuto = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (confirmRestoreAuto) "Tap again to replace current wallet" else "Restore from local auto-backup")
            }
        }
        OutlinedButton(
            onClick = vm::loadExit,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Emergency exit") }
        OutlinedButton(
            onClick = {
                confirmSpend(activity, onAuthenticated = { vm.revealPhrase(true) }, onError = {})
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Reveal recovery phrase") }
        var confirmDelete by remember { mutableStateOf(false) }
        OutlinedButton(
            onClick = {
                if (confirmDelete) vm.deleteWalletConfirmed() else confirmDelete = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (confirmDelete) "Tap again to delete forever" else "Delete wallet",
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = { vm.go(Screen.HOME) }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun Exit(state: KeelUiState, vm: KeelViewModel) {
    val view = LocalView.current
    val activity = view.context as FragmentActivity
    var confirmStart by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Emergency exit", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pulls all funds back on-chain without the Ark server's cooperation. Slow (timelocks) and costs on-chain fees. Only needed if the server is gone or censoring you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.hasPendingExits) {
            Text(
                "Recovery in progress — your sats are moving on-chain. Nothing more to do until the timelock passes.",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.exitVtxos.isNotEmpty()) {
            state.exitVtxos.forEach { exit ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        "${formatSats(exit.amountSats.toLong())} sats",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        exitStateLabel(exit.state) + if (exit.isClaimable) " - claimable" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (exit.isClaimable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = vm::refreshExits, modifier = Modifier.fillMaxWidth()) { Text("Refresh status") }
            if (state.exitVtxos.any { it.isClaimable }) {
                Button(
                    onClick = vm::claimExits,
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Claim on-chain now") }
            }
        }
        if (!state.hasPendingExits) {
            Button(
                onClick = { confirmStart = true },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Start exit for entire wallet") }
        }
        TextButton(onClick = { vm.go(Screen.SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
    if (confirmStart) {
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text("Start emergency exit?") },
            text = { Text("All Ark funds will move on-chain. This cannot be undone and normal sends pause while it completes.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = false
                    confirmSpend(activity, onAuthenticated = vm::startExit, onError = {})
                }) { Text("Start exit", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Reveal(state: KeelUiState, vm: KeelViewModel) {
    SecureWindow()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Recovery phrase", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Anyone with these words can take the funds.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        state.revealWords.forEachIndexed { index, word ->
            Text("${index + 1}. $word", fontFamily = FontFamily.Monospace)
        }
        TextButton(onClick = { vm.go(Screen.SETTINGS) }, modifier = Modifier.fillMaxWidth()) { Text("Hide") }
    }
}

@Composable
private fun SecureWindow() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
private fun PassphraseDialog(
    title: String,
    confirmLabel: String,
    requireConfirm: Boolean,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val valid = first.length >= 8 && (!requireConfirm || first == second)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Minimum 8 characters. Keel never stores it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text("Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (requireConfirm) {
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = it },
                        label = { Text("Repeat passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(first.toCharArray()) }, enabled = valid) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ExportBackupButton(vm: KeelViewModel, label: String, requireAuth: Boolean, fullBackup: Boolean = false) {
    val view = LocalView.current
    val activity = view.context as FragmentActivity
    var showDialog by remember { mutableStateOf(false) }
    var pendingPassphrase by remember { mutableStateOf<CharArray?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val passphrase = pendingPassphrase
        pendingPassphrase = null
        if (uri != null && passphrase != null) {
            if (fullBackup) vm.exportFullBackup(uri, passphrase) else vm.exportEncryptedBackup(uri, passphrase)
        }
    }
    OutlinedButton(
        onClick = {
            if (requireAuth) {
                confirmSpend(activity, onAuthenticated = { showDialog = true }, onError = {})
            } else {
                showDialog = true
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(label) }
    if (showDialog) {
        PassphraseDialog(
            title = "Encrypt backup",
            confirmLabel = "Choose location",
            requireConfirm = true,
            onConfirm = { passphrase ->
                showDialog = false
                pendingPassphrase = passphrase
                launcher.launch(backupFileName(fullBackup))
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun ImportBackupButton(vm: KeelViewModel) {
    val context = LocalContext.current
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingIsFull by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Sniff the magic: KEELDB01 = full wallet backup, KEELBK01 = seed only.
            val isFull = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val magic = ByteArray(8)
                    input.read(magic) == 8 && FullBackupEnvelope.isFullBackup(magic)
                } == true
            }.getOrDefault(false)
            pendingIsFull = isFull
            pendingUri = uri
        }
    }
    OutlinedButton(
        onClick = { launcher.launch(arrayOf("*/*")) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Open encrypted backup file") }
    val uri = pendingUri
    if (uri != null) {
        PassphraseDialog(
            title = if (pendingIsFull) "Decrypt full backup" else "Decrypt backup",
            confirmLabel = "Restore wallet",
            requireConfirm = false,
            onConfirm = { passphrase ->
                pendingUri = null
                if (pendingIsFull) vm.importFullBackup(uri, passphrase) else vm.restoreFromBackup(uri, passphrase)
            },
            onDismiss = { pendingUri = null },
        )
    }
}

private fun backupFileName(fullBackup: Boolean = false): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return if (fullBackup) "keel-full-backup-$stamp.keel" else "keel-backup-$stamp.keel"
}

private fun formatSats(value: Long): String = NumberFormat.getNumberInstance(Locale.US).format(value)

private fun shortAddress(address: String): String =
    if (address.length <= 24) address else address.take(12) + "..." + address.takeLast(8)

private fun formatTime(epochMs: Long): String {
    return SimpleDateFormat("HH:mm", Locale.US).format(Date(epochMs))
}
