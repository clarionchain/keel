/** Home: hero balance, banners, activity, main actions. */
import { h, fmtSats, fmtTime } from "../dom";
import { satsToFiat } from "../price";
import type { App } from "../app";
import type { Movement } from "@secondts/bark/web";

function movementLabel(m: Movement): { text: string; amount: number; pending: boolean } {
  const amount = m.effectiveBalanceSats || m.intendedBalanceSats;
  const pending = m.status !== "finished" && m.status !== "successful" && m.completedAt == null;
  const kind = m.subsystemKind || m.subsystemName;
  let text = kind;
  if (/arkoor|ark/i.test(kind)) text = amount < 0 ? "Ark send" : "Ark receive";
  else if (/lightning/i.test(kind)) text = amount < 0 ? "Lightning send" : "Lightning receive";
  else if (/board/i.test(kind)) text = "Board";
  else if (/offboard/i.test(kind)) text = "Offboard";
  else if (/exit/i.test(kind)) text = "Emergency exit";
  else if (/refresh/i.test(kind)) text = "Refresh";
  return { text, amount, pending };
}

export function HomeScreen(app: App): HTMLElement {
  const s = app.state;
  const b = s.balance;
  const spendable = b?.spendable ?? 0;

  const el = h("div", {});

  el.append(
    h("div", { class: "hero" },
      h("div", { class: "amount" }, `${fmtSats(spendable)} sats`),
      h("div", { class: "fiat" }, satsToFiat(spendable, s.price) || " "),
    ),
    h("div", { class: "hero-actions" },
      h("button", { disabled: !!s.opening, onclick: () => { app.resetSend(); app.go("send"); } }, "Send"),
      h("button", { class: "secondary", disabled: !!s.opening, onclick: () => app.go("receive") }, "Receive"),
    ),
  );

  // Unlock lands here before Wallet.open finishes in the background — say so
  // instead of showing a bare zero balance that could be misread as real.
  if (s.opening) {
    el.append(h("p", { class: "muted small center" }, "Loading…"));
  }

  if (b) {
    const parts: string[] = [];
    if (b.pendingRound > 0) parts.push(`${fmtSats(b.pendingRound)} settling`);
    if (b.lightningLocked > 0) parts.push(`${fmtSats(b.lightningLocked)} Lightning in flight`);
    if (b.boardPending > 0) parts.push(`${fmtSats(b.boardPending)} boarding`);
    if (b.exitPending > 0) parts.push(`${fmtSats(b.exitPending)} exiting`);
    if (parts.length) el.append(h("p", { class: "muted small center" }, parts.join(" · ")));
  }

  if (s.poisoned) {
    el.append(
      h("div", { class: "banner warn row" },
        h("span", { class: "grow" }, "The Ark test server is malfunctioning and crashed the wallet engine (server-side — funds are safe). Reload to retry."),
        h("button", { class: "text", style: "width:auto", onclick: () => location.reload() }, "Reload")),
    );
  }

  if (s.hasPendingExits) {
    el.append(h("button", { class: "danger pulse", onclick: () => void app.loadExit() }, "Emergency exit in progress"));
  }

  if (b && b.expired > 0) {
    el.append(
      h("div", { class: "banner row" },
        h("span", { class: "grow" }, `${fmtSats(b.expired)} sats expired`),
        h("button", { class: "text", style: "width:auto", onclick: () => void app.loadExit() }, "Recover on-chain")),
    );
  } else if (b && b.expiringSoon > 0) {
    el.append(
      h("div", { class: "banner warn row" },
        h("span", { class: "grow" }, `${fmtSats(b.expiringSoon)} sats expire soon — the server will refresh them automatically`),
      ),
    );
  }

  if (b && b.onchain > 0) {
    el.append(
      h("div", { class: "banner info row" },
        h("span", { class: "grow" }, `${fmtSats(b.onchain)} sats on-chain`),
        h("button", { class: "text", style: "width:auto", onclick: () => void app.boardAll() }, "Move to Ark")),
    );
  }

  const empty = b != null && b.total === 0;
  if (empty) {
    el.append(
      h("div", { class: "card center" },
        h("p", {}, "No funds yet"),
        h("button", { class: "secondary", onclick: () => void app.copyAddressAndOpenFaucet() }, "Copy address & get test coins")),
    );
  }

  if (s.history.length > 0) {
    el.append(
      h("h2", {}, "Activity"),
      h("div", { class: "activity" },
        ...s.history.map((m) => {
          const { text, amount, pending } = movementLabel(m);
          return h("div", { class: "item" },
            h("span", {},
              h("div", {}, text),
              h("div", { class: "muted small" }, fmtTime(Date.parse(m.createdAt))),
            ),
            h("span", { class: amount < 0 ? "neg" : "pos" },
              `${amount >= 0 ? "+" : ""}${fmtSats(amount)}`,
              pending ? h("span", { class: "pending" }, " pending") : null,
            ),
          );
        }),
      ),
    );
  }

  if (s.lastSync) {
    el.append(h("p", { class: "muted small center" }, `Synced ${fmtTime(s.lastSync)}`));
  }

  el.append(
    h("div", { class: "footer-nav" },
      h("button", { class: "text", disabled: !!s.opening, onclick: () => void app.refreshHome() }, "Sync"),
      h("button", { class: "text", disabled: !!s.opening, onclick: () => app.go("settings") }, "Settings"),
    ),
  );
  return el;
}
