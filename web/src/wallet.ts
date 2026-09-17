/**
 * KeelWallet: thin wrapper around the Bark WASM SDK, mirroring the Android
 * app's WalletRepository (same UniFFI core, same semantics). Wallet state is
 * persisted by the SDK in IndexedDB under DB_NAME.
 */
import init, {
  OnchainWallet,
  Wallet,
  extractTxFromPsbt,
  generateMnemonic,
  validateMnemonic as sdkValidateMnemonic,
  validateArkAddress as sdkValidateArkAddress,
} from "@secondts/bark/web";
import type {
  ArkInfo,
  Balance,
  Config,
  ExitVtxo,
  Movement,
  PendingBoard,
  Vtxo,
} from "@secondts/bark/web";
// Vite resolves `?url` to the hashed wasm asset URL.
import wasmUrl from "@secondts/bark/web/bark_ffi_wasm_bg.wasm?url";

export const DB_NAME = "keel-signet";
export const NETWORK = "Signet" as const;

const CONFIG: Config = {
  // Second's public Signet ASP is the default.
  serverAddress: "https://ark.signet.2nd.dev",
  esploraAddress: "https://mempool.space/signet/api",
};

/** ~1 day of blocks; VTXOs this close to expiry get refreshed. */
export const SOON_THRESHOLD_BLOCKS = 144;

export interface ExpiryReport {
  tip: number;
  okSats: number;
  soonSats: number;
  expiredSats: number;
  expiredIds: Set<string>;
  needsRefresh: boolean;
  needsRecovery: boolean;
}

export interface KeelBalance {
  spendable: number;
  pendingRound: number;
  lightningLocked: number;
  boardPending: number;
  exitPending: number;
  expired: number;
  expiringSoon: number;
  onchain: number;
  total: number;
}

let wasmReady: Promise<unknown> | null = null;

/** Start (or reuse) wasm compile. Safe to call at page load — does not gate the UI. */
export function initWasm(): Promise<unknown> {
  if (!wasmReady) {
    wasmReady = init({ module_or_path: wasmUrl }).catch((err) => {
      wasmReady = null; // allow Retry to start a fresh fetch/compile
      throw err;
    });
  }
  return wasmReady;
}

// Start compile as soon as this module evaluates — do not wait for main.ts
// to finish importing every screen.
void initWasm();

// Wallet.open blocks on an Ark-server ping inside the ffi, and a cold
// cross-Pacific TLS handshake is most of that cost. Firing a tiny read-only
// GetArkInfo probe the moment the user lands on the unlock screen warms the
// browser's connection pool for the origin, so open's own request skips the
// handshake. Fire-and-forget: a failed probe changes nothing (open retries
// on its own), and re-fires are throttled so a re-visit re-warms an idle
// socket the browser may have reaped.
let lastWarmup = 0;

export function warmServerConnection(): void {
  const now = Date.now();
  if (now - lastWarmup < 30_000) return;
  lastWarmup = now;
  // gRPC-web frame: 5-byte header (uncompressed, len=2) + protobuf `08 01`.
  void fetch(`${CONFIG.serverAddress}/bark_server.ArkService/GetArkInfo`, {
    method: "POST",
    headers: { "content-type": "application/grpc-web+proto" },
    body: new Uint8Array([0, 0, 0, 0, 2, 8, 1]),
  }).catch(() => {});
}

export function newMnemonic(): string {
  return generateMnemonic();
}

export function validMnemonic(phrase: string): boolean {
  try {
    return sdkValidateMnemonic(phrase.trim());
  } catch {
    return false;
  }
}

export class KeelWallet {
  private wallet: Wallet | null = null;
  private onchain: OnchainWallet | null = null;
  private opening: Promise<string> | null = null;

  get isOpen(): boolean {
    return this.wallet != null;
  }

  async open(mnemonic: string, createIfMissing: boolean, skipRecovery: boolean): Promise<string> {
    if (this.wallet) return this.wallet.fingerprint();
    if (this.opening) return this.opening;
    this.opening = this.openInner(mnemonic, createIfMissing, skipRecovery).finally(() => {
      this.opening = null;
    });
    return this.opening;
  }

  private async openInner(mnemonic: string, createIfMissing: boolean, skipRecovery: boolean): Promise<string> {
    await initWasm();
    const wallet = await Wallet.open(NETWORK, mnemonic.trim(), CONFIG, null, {
      runDaemon: false,
      indexedDbName: DB_NAME,
      createIfNotExists: createIfMissing,
      // Never block open on a server handshake. Sync talks to the server later.
      createWithoutServer: true,
      skipRecovery,
    });
    this.wallet = wallet;
    this.onchain = wallet.onchainWallet() ?? null;
    return wallet.fingerprint();
  }

  async close(): Promise<void> {
    const w = this.wallet;
    this.wallet = null;
    this.onchain = null;
    if (w) w.free();
  }

  private requireWallet(): Wallet {
    if (!this.wallet) throw new Error("wallet is closed");
    return this.wallet;
  }

  fingerprint(): string {
    return this.requireWallet().fingerprint();
  }

  async sync(): Promise<void> {
    await this.requireWallet().sync();
  }

  async tipHeight(): Promise<number> {
    if (this.onchain) {
      try {
        return await this.onchain.tipHeight();
      } catch {
        // fall through to esplora
      }
    }
    // Fallback: ask the esplora API directly (same source the wallet uses).
    // NB: Second's esplora serves the API at the root — no /api prefix.
    const res = await fetch("https://esplora.signet.2nd.dev/blocks/tip/height");
    if (!res.ok) throw new Error("chain tip unavailable");
    return Number(await res.text());
  }

  /**
   * All VTXOs in spendable state, INCLUDING expired ones. The SDK's own
   * spendableVtxos() filters expired VTXOs out (0.23), which would make
   * expired funds invisible — so classify from the full list ourselves.
   */
  private async allSpendableVtxos(): Promise<Vtxo[]> {
    const all = await this.requireWallet().vtxos();
    return all.filter((v: Vtxo) => v.state.type === "spendable");
  }

  /**
   * Ids of VTXOs with an active exit. They are leaving Ark, so Ark-side
   * expiry no longer applies: excluding them keeps the expiry banner and
   * refresh scheduling from contradicting the exit-in-progress state.
   */
  private async activelyExitingIds(): Promise<Set<string>> {
    try {
      const exits = await this.requireWallet().getExitVtxos();
      return new Set(
        exits
          .filter((e: ExitVtxo) => e.state.type !== "canceled" && e.state.type !== "vtxo-already-spent")
          .map((e: ExitVtxo) => e.vtxoId),
      );
    } catch {
      return new Set();
    }
  }

  async expiryReport(): Promise<ExpiryReport> {
    const tip = await this.tipHeight();
    const exiting = await this.activelyExitingIds();
    const vtxos = (await this.allSpendableVtxos()).filter((v: Vtxo) => !exiting.has(v.id));
    let okSats = 0;
    let soonSats = 0;
    let expiredSats = 0;
    const expiredIds = new Set<string>();
    for (const v of vtxos) {
      if (tip >= v.expiryHeight) {
        expiredSats += v.amountSats;
        expiredIds.add(v.id);
      } else if (v.expiryHeight - tip <= SOON_THRESHOLD_BLOCKS) {
        soonSats += v.amountSats;
      } else {
        okSats += v.amountSats;
      }
    }
    return {
      tip,
      okSats,
      soonSats,
      expiredSats,
      expiredIds,
      needsRefresh: soonSats > 0,
      needsRecovery: expiredSats > 0,
    };
  }

  /** Balance with expired VTXOs moved out of "spendable" (same rule as Android). */
  async balance(): Promise<KeelBalance> {
    const w = this.requireWallet();
    const b: Balance = await w.balance();
    let report: ExpiryReport | null = null;
    try {
      report = await this.expiryReport();
    } catch {
      // Chain source unreachable — report raw balance rather than fail.
    }
    const expired = report?.expiredSats ?? 0;
    // Compute spendable from our own classification: whether the SDK's
    // spendableSats includes expired VTXOs differs by version, so never
    // subtract — classify the full VTXO list ourselves instead.
    const spendable = report ? report.okSats + report.soonSats : b.spendableSats;
    const lightningLocked = b.pendingLightningSendSats + b.claimableLightningReceiveSats;
    let onchain = 0;
    if (this.onchain) {
      try {
        onchain = (await this.onchain.balance()).confirmedSats;
      } catch {
        // on-chain balance unavailable — non-fatal
      }
    }
    return {
      spendable,
      pendingRound: b.pendingInRoundSats,
      lightningLocked,
      boardPending: b.pendingBoardSats,
      exitPending: b.pendingExitSats,
      expired,
      expiringSoon: report?.soonSats ?? 0,
      onchain,
      total: spendable + b.pendingInRoundSats + lightningLocked + b.pendingBoardSats + b.pendingExitSats + expired,
    };
  }

  async history(): Promise<Movement[]> {
    return this.requireWallet().history();
  }

  async newAddress(): Promise<string> {
    return this.requireWallet().newAddress();
  }

  validateArkAddress(address: string): boolean {
    try {
      sdkValidateArkAddress(address);
      return true;
    } catch {
      return false;
    }
  }

  /** Format plus same-server / delivery check. False is payable-from-Keel failure, not a parse error. */
  async canPayArkAddress(address: string): Promise<boolean> {
    if (!this.validateArkAddress(address)) return false;
    try {
      return await this.requireWallet().validateArkoorAddress(address);
    } catch {
      return false;
    }
  }

  // ---- Lightning receive ----

  async bolt11Invoice(amountSats: number): Promise<{ invoice: string; paymentHash: string }> {
    const inv = await this.requireWallet().bolt11Invoice({ amountSats });
    return { invoice: inv.invoice, paymentHash: inv.paymentHash };
  }

  async isInvoicePaid(paymentHash: string): Promise<boolean> {
    return this.requireWallet().isInvoicePaid(paymentHash);
  }

  async claimAllLightningReceives(): Promise<number> {
    const claimed = await this.requireWallet().tryClaimAllLightningReceives({ wait: false });
    return claimed.length;
  }

  // ---- Lightning send ----

  async estimateLightningSendFee(amountSats: number): Promise<number> {
    return (await this.requireWallet().estimateLightningSendFee(amountSats)).feeSats;
  }

  async payLightningInvoice(invoice: string, amountSats: number | undefined, wait: boolean): Promise<void> {
    await this.requireWallet().payLightningInvoice({ invoice, amountSats, wait });
  }

  // ---- Ark send ----

  async estimateArkFee(amountSats: number): Promise<number> {
    return (await this.requireWallet().estimateArkoorPaymentFee(amountSats)).feeSats;
  }

  async sendArk(address: string, amountSats: number): Promise<void> {
    await this.requireWallet().sendArkoorPayment(address, amountSats);
  }

  // ---- On-chain in/out ----

  async boardFundingAddress(): Promise<string> {
    return (await this.requireWallet().boardFundingAddress()).address;
  }

  async boardAll(): Promise<PendingBoard> {
    return this.requireWallet().boardAll();
  }

  /** Greedy largest-first selection, excluding expired VTXOs. */
  async selectOffboardVtxos(amountSats: number): Promise<string[]> {
    const report = await this.expiryReport();
    const spendable = (await this.allSpendableVtxos())
      .filter((v: Vtxo) => !report.expiredIds.has(v.id))
      .sort((a: Vtxo, b: Vtxo) => b.amountSats - a.amountSats);
    const selected: string[] = [];
    let total = 0;
    for (const v of spendable) {
      selected.push(v.id);
      total += v.amountSats;
      if (total >= amountSats) break;
    }
    if (total < amountSats) throw new Error("insufficient spendable funds for offboard");
    return selected;
  }

  async estimateOffboardFee(address: string, vtxoIds: string[]): Promise<number> {
    return (await this.requireWallet().estimateOffboardFee(address, vtxoIds)).feeSats;
  }

  async offboard(address: string, vtxoIds: string[]): Promise<string> {
    return (await this.requireWallet().offboardVtxos(vtxoIds, address)).txid;
  }

  // ---- VTXO maintenance ----

  /**
   * The permanent expiry fix: at every sync, hand the server a signed
   * "renewal appointment" for EVERY spendable VTXO (delegated refresh).
   * VTXOs already inside the safety margin are refreshed in the next round;
   * all others are scheduled for ~1 day before their own expiry. The server
   * executes them even if the wallet is never opened again — funds cannot
   * silently expire as long as the server lives (and each app open re-arms
   * the next cycle).
   */
  async scheduleRefreshes(): Promise<{ refreshedNow: number; scheduled: number }> {
    const w = this.requireWallet();
    const tip = await this.tipHeight();
    const inRound = new Set((await w.pendingRoundInputVtxos().catch(() => [] as Vtxo[])).map((v: Vtxo) => v.id));
    const exiting = await this.activelyExitingIds();
    const vtxos = (await this.allSpendableVtxos()).filter((v: Vtxo) => !inRound.has(v.id) && !exiting.has(v.id));
    const due: string[] = [];
    const byHeight = new Map<number, string[]>();
    for (const v of vtxos) {
      if (tip >= v.expiryHeight) continue; // expired — only on-chain exit can recover
      const at = v.expiryHeight - SOON_THRESHOLD_BLOCKS;
      if (at <= tip) {
        due.push(v.id);
      } else {
        const list = byHeight.get(at) ?? [];
        list.push(v.id);
        byHeight.set(at, list);
      }
    }
    let refreshedNow = 0;
    let scheduled = 0;
    if (due.length > 0) {
      try {
        await w.refreshVtxosDelegated(due);
        refreshedNow = due.length;
      } catch {
        // leave for next sync
      }
    }
    for (const [height, ids] of byHeight) {
      try {
        await w.refreshVtxosScheduled(ids, height);
        scheduled += ids.length;
      } catch {
        // leave for next sync
      }
    }
    return { refreshedNow, scheduled };
  }

  /** Legacy immediate refresh (kept for the manual "Refresh now" button). */
  async refreshDueVtxos(): Promise<string | null> {
    const w = this.requireWallet();
    const exiting = await this.activelyExitingIds();
    const due = (await w.getVtxosToRefresh()).filter((v: Vtxo) => !exiting.has(v.id));
    if (due.length === 0) return null;
    return (await w.refreshVtxos(due.map((v: Vtxo) => v.id))) ?? null;
  }

  // ---- Emergency exit ----

  async getExitVtxos(): Promise<ExitVtxo[]> {
    return this.requireWallet().getExitVtxos();
  }

  async startExitForEntireWallet(): Promise<void> {
    const w = this.requireWallet();
    // Exit every spendable-state VTXO explicitly, including expired ones
    // (startExitForEntireWallet may skip VTXOs the server already dropped).
    const ids = (await this.allSpendableVtxos()).map((v: Vtxo) => v.id);
    if (ids.length === 0) throw new Error("no funds to exit");
    await w.startExitForVtxosIncludingNonStandard(ids);
  }

  /**
   * Boards and exits sync independently: a poisoned board record (the server
   * forgot it) must not block exit tracking. Returns the boards-sync error
   * message when it fails — non-fatal; the caller may surface it.
   */
  async syncExitsAndBoards(): Promise<string | undefined> {
    const w = this.requireWallet();
    let boardsError: string | undefined;
    try {
      await w.syncPendingBoards();
    } catch (e) {
      boardsError = e instanceof Error ? e.message : String(e);
    }
    try {
      await w.syncExits();
    } catch {
      // transient — retried next sync
    }
    return boardsError;
  }

  /**
   * Advance exit state machines (claim detection, CPFP broadcasts). Talks to
   * the chain source only — never the Ark server — so it works even when the
   * server sync is broken.
   */
  async progressExits(): Promise<void> {
    await this.requireWallet().progressExits({});
  }

  /**
   * Claim every claimable exit back to our own on-chain wallet and broadcast
   * the claim transaction. Returns claimed sats, or 0 when nothing is
   * claimable. Chain-only: an exit can always complete without the server.
   */
  async claimExits(): Promise<number> {
    const w = this.requireWallet();
    const claimable = await w.listClaimableExits();
    if (claimable.length === 0) return 0;
    if (!this.onchain) throw new Error("on-chain wallet unavailable");
    const address = await this.onchain.newAddress();
    const claim = await w.drainExits({ vtxoIds: claimable.map((e: ExitVtxo) => e.vtxoId), address });
    await w.broadcastTx(extractTxFromPsbt(claim.psbtBase64));
    return claimable.reduce((sum: number, e: ExitVtxo) => sum + e.amountSats, 0);
  }

  /** Sync the on-chain wallet against the chain source (claimed exits land here). */
  async syncOnchain(): Promise<void> {
    if (this.onchain) await this.onchain.sync();
  }

  async hasPendingExits(): Promise<boolean> {
    return this.requireWallet().hasPendingExits();
  }

  /** Server-published parameters (VTXO lifetime, round interval, exit delta...). */
  async arkInfo(): Promise<ArkInfo> {
    const info = await this.requireWallet().arkInfo();
    if (!info) throw new Error("server info unavailable");
    return info;
  }
}
