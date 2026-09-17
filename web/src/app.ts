/**
 * App controller: owns state, the wallet, and screen rendering.
 * Mirrors the Android KeelViewModel flow.
 */
import { KeelWallet, initWasm, newMnemonic, validMnemonic, warmServerConnection, type KeelBalance } from "./wallet";
import { fmtSats } from "./dom";
import { encryptBackup, decryptBackup, isKeelBackup, toBase64, fromBase64 } from "./crypto";
import { vaultSave, vaultLoad, vaultClear, vaultDeleteWalletDb } from "./vault";
import { DB_NAME } from "./wallet";
import { parsePayment, type ParsedPayment } from "./parse";
import { fetchPrice, type PriceQuote } from "./price";
import { friendlySendError, isUncertainSendError, recentOutboundFound } from "./send-error";
import type { ExitVtxo, Movement } from "@secondts/bark/web";

export type Screen =
  | "loading"
  | "locked"
  | "welcome"
  | "create"
  | "verify"
  | "restore"
  | "home"
  | "receive"
  | "send"
  | "scan"
  | "settings"
  | "exit";

export type SendPhase = "idle" | "confirm" | "submitting" | "sent" | "reconciling" | "failed_retryable" | "failed_recovery";
export type ReceiveMode = "ark" | "lightning" | "onchain";

/** Second's signet faucet (GitHub sign-in required there). */
export const FAUCET_URL = "https://signet.2nd.dev/";

export interface AppState {
  screen: Screen;
  busy: boolean;
  error?: string;
  notice?: string;
  /** A wallet (encrypted seed) is stored in this browser. */
  hasVault: boolean;
  fingerprint?: string;
  balance?: KeelBalance;
  history: Movement[];
  hasPendingExits: boolean;
  price: PriceQuote | null;
  lastSync?: number;
  /** The wasm engine panicked (poisoned instance) — only a page reload recovers. */
  poisoned?: boolean;
  /** Wallet.open is still finishing in the background after unlock — the home
   * screen is already visible but wallet actions stay disabled until it lands. */
  opening?: boolean;
  // create flow
  pendingWords: string[];
  // receive
  receiveMode: ReceiveMode;
  receiveAddress?: string;
  lightningInvoice?: string;
  lightningPaymentHash?: string;
  lightningPaid: boolean;
  boardAddress?: string;
  // send
  sendInput: string;
  sendAmount: string;
  sendParsed?: ParsedPayment;
  sendFee?: number;
  sendTotal?: number;
  sendVtxoIds?: string[];
  sendPhase: SendPhase;
  /** Persistent send-screen status (not a toast — those auto-dismiss). */
  sendHint?: string;
  // exits
  exits?: ExitVtxo[];
}

const initialState: AppState = {
  screen: "welcome",
  busy: false,
  hasVault: false,
  history: [],
  hasPendingExits: false,
  price: null,
  pendingWords: [],
  receiveMode: "ark",
  lightningPaid: false,
  sendInput: "",
  sendAmount: "",
  sendPhase: "idle",
};

export class App {
  state: AppState = { ...initialState };
  wallet = new KeelWallet();
  private renderFn: () => void = () => {};
  private lightningPoll: number | null = null;

  onRender(fn: () => void) {
    this.renderFn = fn;
  }

  set(patch: Partial<AppState>) {
    Object.assign(this.state, patch);
    this.renderFn();
  }

  /** Mutate state without re-rendering (for live input values). */
  patch(patch: Partial<AppState>) {
    Object.assign(this.state, patch);
  }

  go(screen: Screen) {
    const patch: Partial<AppState> = { screen, error: undefined, notice: undefined };
    // Entering Send after a finished/failed flow starts a clean form.
    if (screen === "send" && (this.state.sendPhase === "sent" || this.state.sendPhase === "failed_recovery")) {
      Object.assign(patch, {
        sendInput: "",
        sendAmount: "",
        sendParsed: undefined,
        sendFee: undefined,
        sendTotal: undefined,
        sendVtxoIds: undefined,
        sendPhase: "idle" as SendPhase,
      });
    }
    if (screen !== "receive") this.stopLightningPoll();
    this.set(patch);
  }

  // ---------- boot / lock ----------

  async boot() {
    // Always land on Welcome — a stored wallet only reorders the options
    // (Unlock becomes primary), it never gates the app. Create, restore,
    // and the APK download stay reachable whether or not a wallet exists.
    // IndexedDB only: do not wait for wasm.
    const saved = await vaultLoad();
    this.set({ screen: "welcome", hasVault: !!saved });
    if (saved) warmServerConnection();
    void fetchPrice().then((p) => this.set({ price: p }));
  }

  /** Wait for the wasm engine. Callers that already showed a real screen
   * (Home / Create) just wait — a hard timeout here turned a slow compile
   * into a fake unlock failure. */
  private async ensureEngine(): Promise<void> {
    await initWasm();
  }

  /**
   * Open (or finish-creating) the wallet. Existing wallets are local IndexedDB.
   * createWithoutServer is set inside KeelWallet.open so a dead Ark server
   * cannot trap this. Recovery runs later via sync, not here.
   */
  private async openWallet(mnemonic: string, createIfMissing: boolean): Promise<string> {
    await this.ensureEngine();
    if (this.wallet.isOpen) return this.wallet.fingerprint();
    try {
      return await this.wallet.open(mnemonic, createIfMissing, true);
    } catch (firstError) {
      if (createIfMissing) throw firstError;
      try {
        return await this.wallet.open(mnemonic, true, true);
      } catch {
        throw firstError;
      }
    }
  }

  /** Local balance first, then network sync with no spinner. */
  private async afterWalletOpen(fingerprint: string): Promise<void> {
    this.set({ opening: false, fingerprint, busy: false });
    if (this.state.screen !== "home") return;
    try {
      const [balance, history, hasPendingExits] = await Promise.all([
        this.wallet.balance(),
        this.wallet.history().catch(() => [] as Movement[]),
        this.wallet.hasPendingExits().catch(() => false),
      ]);
      this.set({
        balance,
        history: history.slice(-20).reverse(),
        hasPendingExits,
      });
    } catch {
      // local read failed — background sync will retry
    }
    void this.refreshHome({ quiet: true });
  }

  /** Passphrase unlock: decrypt, show Home, open the engine in the background. */
  async unlock(passphrase: string) {
    if (this.state.busy) return;
    if (this.wallet.isOpen) {
      this.set({ screen: "home", opening: false, error: undefined, fingerprint: this.wallet.fingerprint() });
      void this.refreshHome({ quiet: true });
      return;
    }
    this.set({ busy: true, error: undefined });
    try {
      const saved = await vaultLoad();
      if (!saved) throw new Error("no wallet stored in this browser");
      const mnemonic = new TextDecoder().decode(await decryptBackup(fromBase64(saved), passphrase));
      // Passphrase is proven (AES-GCM). Home now — never sit on a spinner
      // for wasm compile, Wallet.open, or the 10-minute sync RPC timeout.
      this.set({ busy: false, screen: "home", opening: true, error: undefined });
      const fingerprint = await this.openWallet(mnemonic, false);
      await this.afterWalletOpen(fingerprint);
    } catch (e) {
      this.set({
        busy: false,
        opening: false,
        screen: this.wallet.isOpen ? this.state.screen : "locked",
        error: unlockErrorMessage(e),
      });
    }
  }

  // ---------- create / restore ----------

  async beginCreate() {
    if (this.state.busy) return;
    this.set({ busy: true, error: undefined });
    try {
      await this.ensureEngine();
      const words = newMnemonic().split(" ");
      this.set({ busy: false, pendingWords: words, screen: "create", error: undefined, notice: undefined });
    } catch (e) {
      this.set({ busy: false, error: unlockErrorMessage(e) });
    }
  }

  /** After the user has seen (and optionally verified) the words. */
  async finishCreate(passphrase: string) {
    if (this.state.busy) return;
    this.set({ busy: true, error: undefined });
    try {
      const mnemonic = this.state.pendingWords.join(" ");
      const envelope = await encryptBackup(new TextEncoder().encode(mnemonic), passphrase);
      await vaultSave(toBase64(envelope));
      this.set({ hasVault: true, busy: false, pendingWords: [], screen: "home", opening: true, notice: "Wallet created" });
      const fingerprint = await this.openWallet(mnemonic, true);
      await this.afterWalletOpen(fingerprint);
    } catch (e) {
      this.set({ busy: false, opening: false, screen: this.wallet.isOpen ? "home" : "create", error: safeMessage(e) });
    }
  }

  async restoreFromWords(phrase: string, passphrase: string) {
    if (this.state.busy) return;
    this.set({ busy: true, error: undefined });
    try {
      await this.ensureEngine();
      const mnemonic = phrase.trim().toLowerCase().split(/\s+/).join(" ");
      if (!validMnemonic(mnemonic)) throw new Error("Recovery phrase is not valid BIP39");
      const envelope = await encryptBackup(new TextEncoder().encode(mnemonic), passphrase);
      await vaultSave(toBase64(envelope));
      this.set({ hasVault: true, busy: false, screen: "home", opening: true, notice: "Wallet restored" });
      const fingerprint = await this.openWallet(mnemonic, true);
      await this.afterWalletOpen(fingerprint);
    } catch (e) {
      this.set({ busy: false, opening: false, screen: this.wallet.isOpen ? "home" : "restore", error: safeMessage(e) });
    }
  }

  /** Restore from a KEELBK01 backup file (interchangeable with the Android app). */
  async restoreFromBackupFile(file: File, passphrase: string) {
    if (this.state.busy) return;
    this.set({ busy: true, error: undefined });
    try {
      const bytes = new Uint8Array(await file.arrayBuffer());
      if (!isKeelBackup(bytes)) throw new Error("not a Keel backup file (bad magic)");
      const mnemonic = new TextDecoder().decode(await decryptBackup(bytes, passphrase));
      await this.ensureEngine();
      if (!validMnemonic(mnemonic)) throw new Error("backup decrypted but did not contain a valid recovery phrase");
      const envelope = await encryptBackup(new TextEncoder().encode(mnemonic), passphrase);
      await vaultSave(toBase64(envelope));
      this.set({ hasVault: true, busy: false, screen: "home", opening: true, notice: "Wallet restored from encrypted backup" });
      const fingerprint = await this.openWallet(mnemonic, true);
      await this.afterWalletOpen(fingerprint);
    } catch (e) {
      this.set({ busy: false, opening: false, screen: this.wallet.isOpen ? "home" : "restore", error: safeMessage(e, "Wrong passphrase or corrupt backup file") });
    }
  }

  // ---------- home / sync ----------

  private refreshInFlight = false;

  /**
   * Auto-sync entry point for the poll timer and tab-return: only fires when
   * the wallet is open, the tab is visible, nothing is running, and the last
   * sync wasn't moments ago. Quiet: no spinner, no error toasts — background
   * failures surface on the next foreground action instead.
   */
  maybeAutoSync() {
    if (document.hidden) return;
    if (!this.wallet.isOpen || this.state.busy || this.state.opening || this.state.poisoned) return;
    if (Date.now() - (this.state.lastSync ?? 0) < 5_000) return;
    void this.refreshHome({ quiet: true });
  }

  async refreshHome(opts?: { quiet?: boolean }) {
    if (!this.wallet.isOpen || this.state.poisoned) return;
    if (this.refreshInFlight) return;
    this.refreshInFlight = true;
    const quiet = opts?.quiet ?? false;
    if (!quiet) this.set({ busy: true });
    // Sync is best-effort: a failing sync (e.g. the server forgot a stale
    // pending board and answers "unknown payment") must not block balance and
    // history, which read local state. A later successful sync clears it.
    // Exception: a wasm panic (broken server sending impossible responses)
    // poisons the whole engine — stop touching it and require a reload.
    const syncError = await this.wallet.sync().then(
      () => undefined as string | undefined,
      (e) => {
        if (isWasmPanic(e)) {
          this.set({ busy: false, poisoned: true, error: undefined });
          return undefined;
        }
        return friendlySyncError(safeMessage(e));
      },
    );
    if (this.state.poisoned) { this.refreshInFlight = false; return; }
    try {
      await this.wallet.claimAllLightningReceives().catch(() => 0);
      const boardsError = await this.wallet.syncExitsAndBoards();
      // Exit progression + claiming talk to the chain only (no Ark server):
      // they must keep working even when the server sync is broken, or an
      // exit could never complete.
      await this.wallet.progressExits().catch(() => {});
      const exitClaimedSats = await this.wallet.claimExits().catch(() => 0);
      // On-chain wallet: claimed exits land here.
      await this.wallet.syncOnchain().catch(() => {});
      // Permanent expiry protection: sign delegated "renewal appointments" for
      // every VTXO — the server refreshes them even while the app is closed.
      const refresh = await this.wallet.scheduleRefreshes().catch(() => null);
      const [balance, history, hasPendingExits] = await Promise.all([
        this.wallet.balance(),
        this.wallet.history().catch(() => [] as Movement[]),
        this.wallet.hasPendingExits().catch(() => false),
      ]);
      // Incoming-funds detection: total+onchain only grows when value arrives
      // from outside (own sends shrink it; board/claim/refresh just move it
      // between buckets). First load after open has no baseline — no toast.
      const prev = this.state.balance;
      const prevFunds = prev ? prev.total + prev.onchain : null;
      const newFunds = balance.total + balance.onchain;
      const gained = prevFunds != null && newFunds > prevFunds ? newFunds - prevFunds : 0;
      const patch: Partial<AppState> = {
        busy: false,
        balance,
        history: history.slice(-20).reverse(),
        hasPendingExits,
        lastSync: Date.now(),
        notice: exitClaimedSats > 0
          ? "Exit complete — your sats are back on-chain"
          : gained > 0
            ? `+${fmtSats(gained)} sats received`
            : refresh && refresh.refreshedNow > 0
              ? "Funds nearing expiry are being refreshed by the Ark server"
              : this.state.notice,
      };
      // A stale board record the server forgot is non-fatal: show it, but
      // never let it block balance/history. Quiet background polls never
      // raise sync errors, but a good quiet sync still clears a stale one.
      const syncErr = syncError ?? (boardsError ? friendlySyncError(boardsError) : undefined);
      if (!(quiet && syncErr)) patch.error = syncErr;
      this.set(patch);
    } catch (e) {
      this.set(quiet ? { busy: false } : { busy: false, error: syncError ?? safeMessage(e) });
    }
    this.refreshInFlight = false;
    void fetchPrice().then((p) => this.set({ price: p }));
  }

  // ---------- receive ----------

  private receiveLoading = false;

  /**
   * One-tap faucet flow (mirrors the Android app): copy the CURRENT wallet's
   * Ark address, say so, then open the faucet. Copying on tap guarantees the
   * clipboard holds this wallet's address — never a stale one from a deleted
   * wallet (which cost a user a day of "faucet doesn't work").
   */
  async copyAddressAndOpenFaucet() {
    try {
      let addr = this.state.receiveAddress;
      if (!addr) {
        addr = await this.wallet.newAddress();
        this.patch({ receiveAddress: addr });
      }
      await navigator.clipboard.writeText(addr);
      this.set({ notice: "Ark address copied — paste it into the faucet" });
    } catch {
      this.set({ error: "Couldn't copy the address — copy it from the Receive screen" });
    }
    window.open(FAUCET_URL, "_blank");
  }

  async loadReceive(mode: ReceiveMode) {
    if (this.receiveLoading) return;
    this.receiveLoading = true;
    try {
      this.set({ receiveMode: mode, error: undefined });
      if (mode === "ark" && !this.state.receiveAddress) {
        const address = await this.wallet.newAddress();
        this.set({ receiveAddress: address });
      }
      if (mode === "onchain" && !this.state.boardAddress) {
        const address = await this.wallet.boardFundingAddress();
        this.set({ boardAddress: address });
      }
    } catch (e) {
      this.set({ error: safeMessage(e) });
    } finally {
      this.receiveLoading = false;
    }
  }

  async createLightningInvoice(amountSats: number) {
    this.set({ busy: true, error: undefined });
    try {
      const inv = await this.wallet.bolt11Invoice(amountSats);
      this.set({ busy: false, lightningInvoice: inv.invoice, lightningPaymentHash: inv.paymentHash, lightningPaid: false });
      this.startLightningPoll(inv.paymentHash);
    } catch (e) {
      this.set({ busy: false, error: safeMessage(e) });
    }
  }

  private startLightningPoll(paymentHash: string) {
    this.stopLightningPoll();
    this.lightningPoll = window.setInterval(async () => {
      try {
        if (await this.wallet.isInvoicePaid(paymentHash)) {
          this.stopLightningPoll();
          await this.wallet.claimAllLightningReceives().catch(() => 0);
          this.set({ lightningPaid: true, notice: "Lightning payment received" });
          await this.refreshHome();
        }
      } catch {
        // keep polling
      }
    }, 4000);
  }

  private stopLightningPoll() {
    if (this.lightningPoll != null) {
      clearInterval(this.lightningPoll);
      this.lightningPoll = null;
    }
  }

  // ---------- send ----------

  async prepareSend() {
    const { sendInput } = this.state;
    const parsed = parsePayment(sendInput);
    if (parsed.kind === "unsupported") {
      this.set({ error: parsed.reason ?? "Unsupported payment request", sendParsed: parsed });
      return;
    }
    const amount = Number(this.state.sendAmount) || parsed.amountSats || 0;
    if (amount <= 0) {
      this.set({ error: "Enter an amount in sats", sendParsed: parsed });
      return;
    }
    this.set({ busy: true, error: undefined, sendHint: undefined, sendParsed: parsed });
    try {
      // Expired VTXOs can never be spent; detect before quoting.
      const expiry = await this.wallet.expiryReport();
      if (expiry.needsRecovery && expiry.okSats < amount) {
        this.set({
          busy: false,
          sendPhase: "failed_recovery",
          sendHint: `Some funds have expired and cannot be sent normally. Expired: ${expiry.expiredSats} sats. Recover them on-chain with an emergency exit.`,
        });
        return;
      }
      let fee = 0;
      let vtxoIds: string[] | undefined;
      if (parsed.kind === "ark") {
        if (!this.wallet.validateArkAddress(parsed.original)) {
          throw new Error("Not a valid Signet Ark address");
        }
        if (!(await this.wallet.canPayArkAddress(parsed.original))) {
          throw new Error("This Ark address isn't payable from Keel (wrong server or unsupported). Both wallets must use the same Ark server (ark.signet.2nd.dev).");
        }
        fee = await this.wallet.estimateArkFee(amount);
      } else if (parsed.kind === "lightning") {
        fee = await this.wallet.estimateLightningSendFee(amount);
      } else {
        vtxoIds = await this.wallet.selectOffboardVtxos(amount);
        fee = await this.wallet.estimateOffboardFee(parsed.original, vtxoIds);
      }
      this.set({
        busy: false,
        sendPhase: "confirm",
        sendFee: fee,
        sendTotal: amount + fee,
        sendVtxoIds: vtxoIds,
        sendAmount: String(amount),
        sendHint: undefined,
      });
    } catch (e) {
      this.set({ busy: false, sendPhase: "failed_retryable", sendHint: friendlySendError(safeMessage(e)) });
    }
  }

  async submitSend() {
    const parsed = this.state.sendParsed;
    if (!parsed || parsed.kind === "unsupported") return;
    const amount = Number(this.state.sendAmount);
    const startedAt = Date.now() - 5_000;
    this.set({ busy: true, error: undefined, sendHint: undefined, sendPhase: "submitting" });
    try {
      if (parsed.kind === "ark") {
        await this.wallet.sendArk(parsed.original, amount);
      } else if (parsed.kind === "lightning") {
        await withTimeout(
          this.wallet.payLightningInvoice(parsed.original, parsed.amountSats, true),
          60_000,
          "Lightning payment timed out",
        );
      } else {
        await this.wallet.offboard(parsed.original, this.state.sendVtxoIds ?? []);
      }
      this.set({ busy: false, sendPhase: "sent", sendHint: undefined });
      await this.refreshHome();
    } catch (e) {
      const msg = String((e as Error)?.message ?? e);
      if (isExpiredVtxoRejection(msg)) {
        this.set({
          busy: false,
          sendPhase: "failed_recovery",
          sendHint: "Some funds have expired and cannot be sent normally. Recover them on-chain with an emergency exit.",
        });
        await this.refreshHome({ quiet: true });
        return;
      }
      await this.finishSendAfterError(amount, startedAt, msg);
    }
  }

  /** Sync first (pending arkoor may still land), then show success or the real error. */
  async checkPendingSend() {
    const amount = Number(this.state.sendAmount) || 0;
    this.set({ busy: true, sendHint: "Checking if it went through…" });
    await this.refreshHome({ quiet: true });
    if (recentOutboundFound(this.state.history, amount, Date.now() - 10 * 60_000)) {
      this.set({ busy: false, sendPhase: "sent", sendHint: undefined, error: undefined });
      return;
    }
    this.set({
      busy: false,
      sendPhase: "reconciling",
      sendHint: "Not confirmed yet. Sync to check — retry only if your spendable balance did not drop.",
      error: undefined,
    });
  }

  private async finishSendAfterError(amount: number, startedAt: number, raw: string) {
    this.set({ sendPhase: "reconciling", sendHint: "Checking if it went through…" });
    await this.refreshHome({ quiet: true });
    if (recentOutboundFound(this.state.history, amount, startedAt)) {
      this.set({ busy: false, sendPhase: "sent", sendHint: undefined, error: undefined });
      return;
    }
    const hint = friendlySendError(raw);
    if (isUncertainSendError(raw)) {
      this.set({
        busy: false,
        sendPhase: "reconciling",
        sendHint: "Not confirmed yet. Sync to check — retry only if your spendable balance did not drop.",
        error: undefined,
      });
      return;
    }
    this.set({ busy: false, sendPhase: "failed_retryable", sendHint: hint, error: undefined });
  }

  resetSend() {
    this.set({
      sendInput: "",
      sendAmount: "",
      sendParsed: undefined,
      sendFee: undefined,
      sendTotal: undefined,
      sendVtxoIds: undefined,
      sendPhase: "idle",
      sendHint: undefined,
      error: undefined,
    });
  }

  // ---------- board / exit ----------

  async boardAll() {
    this.set({ busy: true, error: undefined });
    try {
      const board = await this.wallet.boardAll();
      this.set({ busy: false, notice: `Boarding ${board.amountSats} sats into Ark — settles in the next round` });
      await this.refreshHome();
    } catch (e) {
      this.set({ busy: false, error: safeMessage(e) });
    }
  }

  async refreshDue() {
    this.set({ busy: true, error: undefined });
    try {
      const txid = await this.wallet.refreshDueVtxos();
      this.set({
        busy: false,
        notice: txid ? "Refreshing expiring funds in the next Ark round" : "No funds need a refresh right now",
      });
      await this.refreshHome();
    } catch (e) {
      this.set({ busy: false, error: safeMessage(e) });
    }
  }

  async loadExit() {
    this.set({ busy: true, error: undefined, screen: "exit" });
    try {
      await this.wallet.syncExitsAndBoards();
      const exits = await this.wallet.getExitVtxos();
      this.set({ busy: false, exits });
    } catch (e) {
      this.set({ busy: false, error: safeMessage(e) });
    }
  }

  async startExit() {
    this.set({ busy: true, error: undefined });
    try {
      await this.wallet.startExitForEntireWallet();
      this.set({ busy: false, notice: "Emergency exit started" });
      await this.loadExit();
      await this.refreshHome();
    } catch (e) {
      this.set({ busy: false, error: safeMessage(e) });
    }
  }

  /** Manual claim for exits whose timelock has passed (auto-claim also runs at every sync). */
  async claimExits() {
    this.set({ busy: true, error: undefined });
    try {
      await this.wallet.progressExits();
      const sats = await this.wallet.claimExits();
      this.set({
        busy: false,
        notice: sats > 0 ? "Exit complete — your sats are back on-chain" : "No exits are ready to claim yet",
      });
      await this.loadExit();
      await this.refreshHome();
    } catch (e) {
      this.set({ busy: false, error: safeMessage(e) });
    }
  }

  // ---------- settings ----------

  async revealWords(passphrase: string): Promise<string[]> {
    const saved = await vaultLoad();
    if (!saved) throw new Error("no wallet stored");
    const mnemonic = new TextDecoder().decode(await decryptBackup(fromBase64(saved), passphrase));
    return mnemonic.split(" ");
  }

  async exportSeedBackup(passphrase: string) {
    const saved = await vaultLoad();
    if (!saved) throw new Error("no wallet stored");
    // Re-encrypt with the export passphrase so the file carries its own secret.
    const mnemonic = await decryptBackup(fromBase64(saved), passphrase);
    const envelope = await encryptBackup(mnemonic, passphrase);
    const blob = new Blob([envelope.buffer as ArrayBuffer], { type: "application/octet-stream" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `keel-backup-${new Date().toISOString().slice(0, 16).replace(/[-:T]/g, "")}.keel`;
    a.click();
    URL.revokeObjectURL(a.href);
  }

  async deleteWallet() {
    try {
      await this.wallet.close();
    } catch {
      // already closed
    }
    await vaultClear();
    await vaultDeleteWalletDb(DB_NAME);
    this.state = { ...initialState, price: this.state.price };
    this.set({ screen: "welcome", notice: "Wallet deleted from this browser" });
  }
}

function isExpiredVtxoRejection(message: string): boolean {
  const m = message.toLowerCase();
  return m.includes("expired") && (m.includes("vtxo") || m.includes("height"));
}

/** A Rust panic trapped in wasm ("RuntimeError: unreachable"). After this, the
 *  whole wasm instance is poisoned — every further call may fail. Seen in the
 *  wild when the Ark server, mid-outage, answers sync with impossible data. */
function isWasmPanic(e: unknown): boolean {
  return e instanceof WebAssembly.RuntimeError || /unreachable executed|RuntimeError/i.test(String((e as Error)?.message ?? e));
}

/** Server-side chain desync (Ark server's node behind/reset): translate the
 *  raw "chain tip X is before wallet birthday Y" rejection into plain words. */
function friendlySyncError(msg: string): string {
  if (/before wallet birthday|created after the tip/i.test(msg)) {
    return "The Ark test server's chain is out of sync (server-side issue, not your wallet). Funds are safe — try again later.";
  }
  // Second's server forgot a stale pending board/payment (its 2026-09 fork).
  // Harmless to funds; the record only clears by rebuilding from the server's
  // current view.
  if (/unknown payment/i.test(msg)) {
    return "The Ark server lost track of an old payment record. Your funds are safe. If this stays, restore from your seed backup to resync.";
  }
  return msg;
}

/** Unlock errors, phrased for the three causes we actually see in the field. */
function unlockErrorMessage(e: unknown): string {
  const raw = safeMessage(e, "Wrong passphrase or corrupt wallet data");
  if (/already in progress/i.test(raw)) {
    return "Wallet setup is still finishing — wait a few seconds, then unlock again";
  }
  if (/parse or connect|failed to fetch|networkerror|timed? ?out|unreachable|connection/i.test(raw)) {
    return "Can't reach the Ark server right now. Your wallet is safely stored — check your connection and try again.";
  }
  return raw;
}

function safeMessage(e: unknown, fallback?: string): string {
  const raw = String((e as Error)?.message ?? e ?? "unknown error");
  const lowered = raw.toLowerCase();
  if (["abandon", "mnemonic", "seed", "preimage"].some((w) => lowered.includes(w)) && raw.split(" ").length >= 8) {
    return "A wallet error occurred (details redacted)";
  }
  if (fallback && /tag|decrypt|magic|passphrase/i.test(raw)) return fallback;
  return raw.slice(0, 280);
}

async function withTimeout<T>(p: Promise<T>, ms: number, message: string): Promise<T> {
  let timer: number;
  const timeout = new Promise<never>((_, reject) => {
    timer = window.setTimeout(() => reject(new Error(message)), ms);
  });
  try {
    return await Promise.race([p, timeout]);
  } finally {
    clearTimeout(timer!);
  }
}
