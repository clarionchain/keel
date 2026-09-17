/** Receive: Ark address, Lightning invoice, on-chain board. */
import { h } from "../dom";
import { qrCanvas } from "../qr";
import type { App, ReceiveMode } from "../app";

function copyButton(text: string): HTMLElement {
  return h("button", {
    class: "secondary",
    onclick: async (e) => {
      await navigator.clipboard.writeText(text);
      (e.target as HTMLElement).textContent = "Copied";
    },
  }, "Copy");
}

export function ReceiveScreen(app: App): HTMLElement {
  const s = app.state;
  const el = h("div", {}, h("h1", {}, "Receive"));

  const tabs = h("div", { class: "tabs" });
  const modes: [ReceiveMode, string][] = [["ark", "Ark"], ["lightning", "Lightning"], ["onchain", "On-chain"]];
  for (const [mode, label] of modes) {
    tabs.append(h("button", {
      class: s.receiveMode === mode ? "active" : "",
      onclick: () => void app.loadReceive(mode),
    }, label));
  }
  el.append(tabs);

  const body = h("div", {});
  el.append(body);

  void (async () => {
    if (s.receiveMode === "ark") {
      if (!s.receiveAddress) await app.loadReceive("ark");
      const addr = app.state.receiveAddress;
      if (!addr) return;
      body.append(
        await qrCanvas(addr),
        h("p", { class: "small mono wordbreak center" }, addr),
        copyButton(addr),
        h("button", { class: "secondary", onclick: () => void app.copyAddressAndOpenFaucet() }, "Copy address & get test coins"),
        h("p", { class: "muted small center" }, "Ark address — instant off-chain receives"),
      );
    } else if (s.receiveMode === "lightning") {
      if (s.lightningInvoice) {
        body.append(
          await qrCanvas(s.lightningInvoice),
          h("p", { class: "small mono wordbreak center" }, s.lightningInvoice.slice(0, 48) + "…"),
          copyButton(s.lightningInvoice),
          s.lightningPaid
            ? h("p", { class: "success center" }, "Paid")
            : h("p", { class: "muted small center" }, "Waiting for payment…"),
          h("button", { class: "text", onclick: () => app.set({ lightningInvoice: undefined, lightningPaymentHash: undefined, lightningPaid: false }) }, "New invoice"),
        );
      } else {
        const amount = h("input", { type: "number", placeholder: "Amount (sats)", inputmode: "numeric" }) as HTMLInputElement;
        body.append(
          amount,
          h("button", {
            onclick: () => {
              const v = Number(amount.value);
              if (v > 0) void app.createLightningInvoice(v);
            },
          }, "Create invoice"),
        );
      }
    } else {
      if (!s.boardAddress) await app.loadReceive("onchain");
      const addr = app.state.boardAddress;
      if (!addr) return;
      body.append(
        await qrCanvas(addr),
        h("p", { class: "small mono wordbreak center" }, addr),
        copyButton(addr),
        h("p", { class: "muted small center" }, "Send signet bitcoin here, then use “Move to Ark” on Home"),
      );
    }
  })();

  el.append(h("button", { class: "text", onclick: () => app.go("home") }, "Back"));
  return el;
}
