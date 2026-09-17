/** Settings + emergency exit screens. */
import { h, shortAddr, fmtSats } from "../dom";
import { APK_FILE, APK_VERSION } from "../apk";
import type { App } from "../app";
import type { ArkInfo, ExitVtxo } from "@secondts/bark/web";

function exitStateLabel(v: ExitVtxo): string {
  const t = v.state.type;
  switch (t) {
    case "start": return "started — confirming on-chain";
    case "processing": return "confirming on-chain";
    case "awaiting-delta": return "waiting on timelock (~1 day)";
    case "claimable": return "ready to claim on-chain";
    case "claim-in-progress": return "claiming…";
    case "claimed": return "done — funds on-chain";
    case "vtxo-already-spent": return "already spent";
    case "canceled": return "canceled";
    default: return t;
  }
}

export function SettingsScreen(app: App): HTMLElement {
  const s = app.state;
  const el = h("div", {},
    h("h1", {}, "Settings"),
    h("div", { class: "card" },
      h("div", { class: "spread" }, h("span", { class: "k" }, "Network"), h("span", {}, "Signet (test coins)")),
      h("div", { class: "spread" }, h("span", { class: "k" }, "Wallet"), h("span", { class: "mono small" }, shortAddr(s.fingerprint ?? "—"))),
      h("div", { class: "spread" }, h("span", { class: "k" }, "Engine"), h("span", {}, "Bark WASM 0.23.0")),
      h("div", { class: "spread" }, h("span", { class: "k" }, "App"), h("span", {}, "Keel PWA 0.1.27")),
    ),
    h("div", { class: "card", id: "server-info" }),
  );

  // Server-published parameters (VTXO lifetime, round cadence, exit timelock).
  void app.wallet.arkInfo().then((info: ArkInfo) => {
    const days = (blocks: number) => (blocks / 144).toFixed(1);
    const card = el.querySelector("#server-info");
    if (!card) return;
    card.replaceChildren(
      h("div", { class: "spread" }, h("span", { class: "k" }, "Ark server"), h("span", { class: "small" }, "ark.signet.2nd.dev")),
      h("div", { class: "spread" }, h("span", { class: "k" }, "VTXO lifetime"), h("span", {}, `${info.vtxoLifetime} blocks (~${days(info.vtxoLifetime)} days)`)),
      h("div", { class: "spread" }, h("span", { class: "k" }, "Round interval"), h("span", {}, `${info.roundIntervalSecs}s`)),
      h("div", { class: "spread" }, h("span", { class: "k" }, "Exit timelock"), h("span", {}, `${info.vtxoExitDelta} blocks (~${days(info.vtxoExitDelta)} days)`)),
    );
  }).catch(() => { /* server info unavailable — non-fatal */ });

  // Reveal words (passphrase-gated)
  const revealPass = h("input", { type: "password", placeholder: "Passphrase to reveal words" }) as HTMLInputElement;
  const wordsBox = h("div", {});
  el.append(
    h("h2", {}, "Recovery"),
    revealPass,
    h("button", {
      class: "secondary",
      onclick: async () => {
        try {
          const words = await app.revealWords(revealPass.value);
          wordsBox.replaceChildren(
            h("div", { class: "words" },
              ...words.map((w, i) => h("div", { class: "word" }, h("span", { class: "n" }, String(i + 1)), w))),
          );
        } catch {
          app.set({ error: "Wrong passphrase" });
        }
      },
    }, "Reveal recovery words"),
    wordsBox,
    h("button", {
      class: "secondary",
      onclick: async () => {
        try {
          await app.exportSeedBackup(revealPass.value);
          app.set({ notice: "Encrypted backup downloaded — keep the passphrase safe, it is not stored anywhere" });
        } catch {
          app.set({ error: "Wrong passphrase" });
        }
      },
    }, "Download encrypted backup (.keel)"),
    h("p", { class: "muted small" }, "The .keel file is interchangeable with the Android app's seed backup."),
  );

  el.append(
    h("h2", {}, "Advanced"),
    h("button", { class: "secondary", onclick: () => void app.loadExit() }, "Emergency exit"),
    h("a", { class: "btn secondary", href: APK_FILE, download: APK_FILE }, `Get the Android app v${APK_VERSION}`),
  );

  // Delete wallet (two-tap)
  const del = h("button", { class: "danger" }, "Delete wallet from this browser") as HTMLButtonElement;
  let armed = false;
  del.addEventListener("click", () => {
    if (!armed) {
      armed = true;
      del.textContent = "Tap again to permanently delete";
      return;
    }
    void app.deleteWallet();
  });
  el.append(h("h2", {}, "Danger zone"), del);

  el.append(h("button", { class: "text", onclick: () => app.go("home") }, "Back"));
  return el;
}

export function ExitScreen(app: App): HTMLElement {
  const s = app.state;
  const el = h("div", {},
    h("h1", {}, "Emergency exit"),
    h("p", { class: "muted small" },
      "Pulls your funds back on-chain without the Ark server. Transactions broadcast now; you can spend them after a timelock (about a day on signet)."),
  );

  const exits = s.exits ?? [];
  const inProgress = exits.filter((v) => !["claimed", "canceled", "vtxo-already-spent"].includes(v.state.type));
  if (inProgress.length > 0) {
    const sats = inProgress.reduce((sum, v) => sum + v.amountSats, 0);
    el.append(
      h("div", { class: "banner info row" },
        h("span", { class: "grow" }, `Recovery in progress — ${fmtSats(sats)} sats moving on-chain. Nothing more to do until the timelock passes.`)),
    );
  }

  if (exits.length > 0) {
    el.append(h("div", { class: "card" },
      ...exits.map((v) =>
        h("div", { class: "spread" },
          h("span", { class: "mono small" }, shortAddr(v.vtxoId)),
          h("span", {}, `${v.amountSats} sats — ${exitStateLabel(v)}`))),
    ));
  } else {
    el.append(h("p", { class: "muted" }, "No exits in progress."));
  }

  // Claimable exits are auto-claimed at every sync; this button is the
  // visible manual path (chain-only — works even if the Ark server is down).
  if (exits.some((v) => v.state.type === "claimable")) {
    el.append(h("button", { onclick: () => void app.claimExits() }, "Claim on-chain now"));
  }

  // Only offer "Start…" when there is something left to exit. Showing it after
  // the exit was already started reads as if the tap did nothing.
  const spendableLeft = s.balance?.spendable ?? 0;
  if (spendableLeft > 0 || exits.length === 0) {
    const start = h("button", { class: "danger" }, "Start exit for entire wallet") as HTMLButtonElement;
    let armed = false;
    start.addEventListener("click", () => {
      if (!armed) {
        armed = true;
        start.textContent = "Tap again to confirm — this moves ALL funds on-chain";
        return;
      }
      void app.startExit();
    });
    el.append(start);
  }
  el.append(h("button", { class: "text", onclick: () => app.go("settings") }, "Back"));
  return el;
}
