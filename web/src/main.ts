import { initWasm } from "./wallet";
import "./style.css";
import { App, type Screen } from "./app";
import { h } from "./dom";
import { LockedScreen, WelcomeScreen, CreateScreen, RestoreScreen } from "./ui/auth";
import { HomeScreen } from "./ui/home";
import { ReceiveScreen } from "./ui/receive";
import { SendScreen, ScanScreen } from "./ui/send";
import { SettingsScreen, ExitScreen } from "./ui/settings";

const app = new App();
const root = document.getElementById("app")!;

// Transient error/notice feedback renders as a floating bottom toast
// (snackbar pattern): it floats above content instead of pushing the layout
// down, and auto-dismisses — info after ~5s, errors after ~8s. Live toast
// elements are kept in a map keyed by message so unrelated re-renders (e.g.
// a price tick) neither replay the animation nor reset the dismiss timer.
const liveToasts = new Map<string, { el: HTMLElement; timer: number }>();

function toast(kind: "error" | "info", msg: string): HTMLElement {
  const key = `${kind}:${msg}`;
  const live = liveToasts.get(key);
  if (live) return live.el;
  const clear = () => {
    const entry = liveToasts.get(key);
    if (entry) window.clearTimeout(entry.timer);
    liveToasts.delete(key);
    if (kind === "error") app.set({ error: undefined });
    else app.set({ notice: undefined });
  };
  const el = h("div", { class: `toast ${kind}`, role: "alert" },
    h("span", { class: "dot" }),
    h("span", { class: "msg" }, msg),
    h("button", { class: "x", onclick: clear }, "✕"),
  );
  const timer = window.setTimeout(clear, kind === "error" ? 8000 : 5000);
  liveToasts.set(key, { el, timer });
  return el;
}

function chrome(content: HTMLElement): HTMLElement {
  const s = app.state;
  const toasts: HTMLElement[] = [];
  if (s.error) toasts.push(toast("error", s.error));
  if (s.notice) toasts.push(toast("info", s.notice));
  const el = h("div", { style: "display:flex;flex-direction:column;flex:1" },
    h("div", { class: "header" },
      h("span", { class: "badge" }, "Signet — test coins"),
      h("img", { class: "header-logo", src: "icon-192.png", alt: "Keel" }),
    ),
    s.busy ? h("div", { class: "spinner" }) : null,
    content,
    toasts.length ? h("div", { class: "toast-stack" }, ...toasts) : null,
  );
  return el;
}

function screen(): HTMLElement {
  switch (app.state.screen as Screen) {
    // Boot no longer parks here — Welcome is first paint. Keep a harmless
    // fallback so a stale state never becomes a dead-end spinner.
    case "loading": return WelcomeScreen(app);
    case "locked": return LockedScreen(app);
    case "welcome": return WelcomeScreen(app);
    case "create": return CreateScreen(app);
    case "verify": return CreateScreen(app);
    case "restore": return RestoreScreen(app);
    case "home": return HomeScreen(app);
    case "receive": return ReceiveScreen(app);
    case "send": return SendScreen(app);
    case "scan": return ScanScreen(app);
    case "settings": return SettingsScreen(app);
    case "exit": return ExitScreen(app);
  }
}

function render() {
  root.replaceChildren(chrome(screen()));
}

app.onRender(render);

// Self-update: when a new service worker takes control, reload once so the
// user never runs a stale bundle. Auto-reload only on screens with no
// in-flight work; anywhere else, offer a tap-to-update notice instead.
if ("serviceWorker" in navigator) {
  let refreshing = false;
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    if (refreshing) return;
    refreshing = true;
    const s = app.state;
    const safe = !s.busy && !s.opening && (s.screen === "locked" || s.screen === "welcome" || s.screen === "home");
    if (safe) {
      window.location.reload();
    } else {
      app.set({ notice: "Update ready — reopen the app or tap Sync to apply it" });
    }
  });
}

// Keep the balance live while the app is open: sync on tab return (e.g. back
// from the faucet) and poll every 30s while visible, so incoming sats appear
// on their own with a "+X sats received" toast — no manual Sync needed.
// maybeAutoSync self-guards: open wallet, visible tab, idle, not just-synced.
document.addEventListener("visibilitychange", () => app.maybeAutoSync());
window.setInterval(() => app.maybeAutoSync(), 30_000);

// Show Welcome on the first frame. Wasm compiles in the background; Unlock
// waits for it only after the passphrase checks out — never a launch spinner.
void initWasm();
void app.boot();
render();
