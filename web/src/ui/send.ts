/** Send flow: input → review → confirm → full-page success. */
import { h, fmtSats } from "../dom";
import { satsToFiat } from "../price";
import { startScan, type ScanHandle } from "../qr";
import type { App } from "../app";

export function SendScreen(app: App): HTMLElement {
  const s = app.state;

  if (s.sendPhase === "sent") {
    const amount = Number(s.sendAmount) || 0;
    return h("div", { class: "success-page" },
      h("div", { class: "bigcheck" }, "✓"),
      h("h1", {}, "Sent"),
      amount > 0 ? h("div", { class: "hero" },
        h("div", { class: "amount" }, `${fmtSats(amount)} sats`),
        h("div", { class: "fiat" }, satsToFiat(amount, s.price) || " ")) : null,
      s.sendFee != null ? h("p", { class: "muted small" }, `Fee ${fmtSats(s.sendFee)} sats`) : null,
      h("button", {
        onclick: () => { app.resetSend(); app.go("home"); },
      }, "Done"),
    );
  }

  const input = h("input", {
    placeholder: "Ark address, Lightning invoice, or bitcoin address",
    value: s.sendInput,
    autocomplete: "off",
  }) as HTMLInputElement;
  input.addEventListener("input", () => app.patch({ sendInput: input.value, sendParsed: undefined, sendPhase: "idle", sendHint: undefined }));

  const amount = h("input", {
    type: "number",
    placeholder: "Amount (sats)",
    inputmode: "numeric",
    value: s.sendAmount,
  }) as HTMLInputElement;
  amount.addEventListener("input", () => app.patch({ sendAmount: amount.value }));

  const el = h("div", {},
    h("h1", {}, "Send"),
    h("div", { class: "row" },
      h("div", { class: "grow" }, input),
      h("button", { class: "secondary", style: "width:auto;padding:14px 18px", onclick: () => app.go("scan") }, "Scan"),
    ),
    amount,
    h("p", { class: "muted small" }, satsToFiat(Number(s.sendAmount) || 0, s.price)),
  );

  if (s.sendPhase === "idle") {
    el.append(h("button", { onclick: () => void app.prepareSend(), disabled: s.busy }, "Review"));
  }

  if (s.sendPhase === "confirm" && s.sendParsed) {
    const kindLabel = s.sendParsed.kind === "lightning" ? "Lightning"
      : s.sendParsed.kind === "onchain" ? "On-chain (offboard)" : "Ark";
    el.append(
      h("div", { class: "card" },
        h("div", { class: "spread" }, h("span", { class: "k" }, "Type"), h("span", {}, kindLabel)),
        h("div", { class: "spread" }, h("span", { class: "k" }, "Amount"), h("span", {}, `${fmtSats(Number(s.sendAmount))} sats`)),
        h("div", { class: "spread" }, h("span", { class: "k" }, "Fee"), h("span", {}, `${fmtSats(s.sendFee ?? 0)} sats`)),
        h("div", { class: "spread" }, h("span", { class: "k" }, "Total"), h("span", {}, `${fmtSats(s.sendTotal ?? 0)} sats`)),
        h("div", { class: "spread" }, h("span", { class: "k" }, "Network"), h("span", {}, "Signet")),
        h("p", { class: "error small" }, "Cannot be reversed"),
      ),
      h("button", { onclick: () => void app.submitSend(), disabled: s.busy }, "Confirm and send"),
    );
  }

  if (s.sendPhase === "submitting") {
    el.append(h("div", { class: "spinner" }), h("p", { class: "muted center" }, "Sending…"));
  }

  if (s.sendPhase === "reconciling") {
    el.append(
      h("div", { class: "banner info row" },
        h("span", { class: "grow" }, s.sendHint ?? "Checking if it went through…"),
      ),
      h("button", { class: "secondary", onclick: () => void app.checkPendingSend(), disabled: s.busy }, "Sync"),
    );
  }

  if (s.sendPhase === "failed_retryable") {
    el.append(
      h("div", { class: "banner row" },
        h("span", { class: "grow" }, s.sendHint ?? "Send failed"),
      ),
      h("button", { onclick: () => void app.prepareSend(), disabled: s.busy }, "Retry"),
      h("button", { class: "text", onclick: () => app.resetSend() }, "Start over"),
    );
  }

  if (s.sendPhase === "failed_recovery") {
    el.append(
      h("div", { class: "banner row" },
        h("span", { class: "grow" }, s.sendHint ?? "Cannot send"),
      ),
      h("button", { onclick: () => void app.loadExit() }, "Recover on-chain"),
      h("button", { class: "text", onclick: () => app.resetSend() }, "Start over"),
    );
  }

  el.append(h("button", { class: "text", onclick: () => app.go("home") }, "Back"));
  return el;
}

export function ScanScreen(app: App): HTMLElement {
  const video = h("video", { class: "scanner", playsinline: true, muted: true }) as HTMLVideoElement;
  const status = h("p", { class: "muted small center" }, "Point the camera at a QR code");
  let handle: ScanHandle | null = null;

  const el = h("div", {},
    h("h1", {}, "Scan"),
    video,
    status,
    h("button", {
      class: "text",
      onclick: () => { handle?.stop(); app.go("send"); },
    }, "Cancel"),
  );

  void startScan(
    video,
    (text) => {
      app.set({ sendInput: text, sendParsed: undefined, sendPhase: "idle" });
      app.go("send");
    },
    (msg) => { status.textContent = msg; },
  ).then((hh) => { handle = hh; });

  return el;
}
