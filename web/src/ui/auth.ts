/** Auth screens: locked, welcome, create (show words), verify, restore. */
import { h } from "../dom";
import { APK_FILE, APK_VERSION } from "../apk";
import { warmServerConnection } from "../wallet";
import type { App } from "../app";

function passphraseField(placeholder = "Passphrase (min 8 characters)"): HTMLInputElement {
  // Mobile keyboards otherwise auto-capitalize the first letter or append a
  // trailing space even in password fields — the saved passphrase then never
  // matches what the user believes they typed. Kill every keyboard "helper".
  return h("input", {
    type: "password",
    placeholder,
    autocomplete: "off",
    autocapitalize: "none",
    autocorrect: "off",
    spellcheck: "false",
  }) as HTMLInputElement;
}

export function LockedScreen(app: App): HTMLElement {
  const pass = passphraseField("Passphrase");
  // The user is about to unlock — warm the Ark server connection while they
  // type so Wallet.open's built-in server ping skips the TLS handshake.
  pass.addEventListener("focus", warmServerConnection);
  const submit = () => {
    if (pass.value.length >= 8) void app.unlock(pass.value);
  };
  pass.addEventListener("keydown", (e) => {
    if (e.key === "Enter") submit();
  });
  // Escape hatch, two-tap so it can't fire accidentally: the locked screen
  // must never be a dead end (forgotten passphrase, interrupted creation,
  // someone else's wallet in a shared browser profile).
  let armed = false;
  let armTimer = 0;
  const reset = h("button", {
    class: "text small",
    onclick: () => {
      if (!armed) {
        armed = true;
        reset.textContent = "Tap again to erase the wallet in this browser";
        armTimer = window.setTimeout(() => {
          armed = false;
          reset.textContent = "Use a different wallet";
        }, 5000);
        return;
      }
      window.clearTimeout(armTimer);
      void app.deleteWallet();
    },
  }, "Use a different wallet");
  const el = h(
    "div",
    {},
    h("div", { class: "hero" },
      h("h1", {}, "Keel"),
      h("p", { class: "muted" }, "Locked"),
      h("p", { class: "muted small" }, "A wallet already exists in this browser")),
    pass,
    h("button", { onclick: submit }, "Unlock"),
    reset,
    h("button", { class: "text small", onclick: () => app.go("welcome") }, "Back"),
  );
  setTimeout(() => pass.focus(), 50);
  return el;
}

export function WelcomeScreen(app: App): HTMLElement {
  const hasVault = app.state.hasVault;
  // Creating over an existing vault would silently replace it — arm first,
  // then wipe the old wallet and start fresh on the confirming tap.
  let armed = false;
  let armTimer = 0;
  const createLabel = "Create new wallet";
  const createBtn = h("button", {
    class: hasVault ? "secondary" : "",
    onclick: () => {
      if (!hasVault) {
        void app.beginCreate();
        return;
      }
      if (!armed) {
        armed = true;
        createBtn.textContent = "This replaces the wallet stored here — tap again";
        armTimer = window.setTimeout(() => {
          armed = false;
          createBtn.textContent = createLabel;
        }, 5000);
        return;
      }
      window.clearTimeout(armTimer);
      void (async () => {
        await app.deleteWallet();
        await app.beginCreate();
      })();
    },
  }, createLabel);
  return h(
    "div",
    {},
    h("div", { class: "hero" },
      h("h1", {}, "Keel"),
      h("p", { class: "muted" }, "Self-custodial Ark wallet"),
      h("p", { class: "muted small" }, "Test network only — never send real bitcoin")),
    hasVault ? h("button", { onclick: () => app.go("locked") }, "Unlock your wallet") : null,
    createBtn,
    h("button", { class: "secondary", onclick: () => app.go("restore") }, "Restore existing wallet"),
    h("div", { class: "card" },
      h("div", { class: "small muted" }, "On Android? Get the native app:"),
      h("a", { class: "btn secondary", href: APK_FILE, download: APK_FILE }, `Download Android APK v${APK_VERSION}`)),
  );
}

export function CreateScreen(app: App): HTMLElement {
  const words = app.state.pendingWords;
  const pass = passphraseField("Set a passphrase (encrypts your wallet in this browser)");
  const pass2 = passphraseField("Repeat passphrase");
  const grid = h(
    "div",
    { class: "words" },
    ...words.map((w, i) => h("div", { class: "word" }, h("span", { class: "n" }, String(i + 1)), w)),
  );
  return h(
    "div",
    {},
    h("h1", {}, "Your 12 words"),
    h("p", { class: "muted small" }, "Write them down on paper. They are the only way to recover your wallet if this browser's data is lost. Never share them."),
    grid,
    pass,
    pass2,
    h("button", {
      onclick: () => {
        if (pass.value.length < 8) {
          app.set({ error: "Passphrase must be at least 8 characters" });
          return;
        }
        if (pass.value !== pass2.value) {
          app.set({ error: "Passphrases do not match" });
          return;
        }
        void app.finishCreate(pass.value);
      },
    }, "I wrote them down — create wallet"),
    h("button", { class: "text", onclick: () => app.go("welcome") }, "Back"),
  );
}

export function RestoreScreen(app: App): HTMLElement {
  const words = h("textarea", { rows: "3", placeholder: "Recovery phrase (12 words)", autocomplete: "off" }) as HTMLTextAreaElement;
  const pass = passphraseField("Set a passphrase for this browser");
  const file = h("input", { type: "file", accept: "*/*", class: "small" }) as HTMLInputElement;
  const filePass = passphraseField("Backup file passphrase");
  return h(
    "div",
    {},
    h("h1", {}, "Restore"),
    h("div", { class: "card" },
      h("div", { class: "small muted" }, "From an encrypted backup file (.keel — works with the Android app's seed backup):"),
      file,
      filePass,
      h("button", {
        class: "secondary",
        onclick: () => {
          const f = file.files?.[0];
          if (!f) {
            app.set({ error: "Choose a backup file first" });
            return;
          }
          if (filePass.value.length < 8) {
            app.set({ error: "Enter the backup passphrase" });
            return;
          }
          void app.restoreFromBackupFile(f, filePass.value);
        },
      }, "Open backup file")),
    h("p", { class: "muted small center" }, "or"),
    words,
    pass,
    h("button", {
      onclick: () => {
        if (pass.value.length < 8) {
          app.set({ error: "Passphrase must be at least 8 characters" });
          return;
        }
        void app.restoreFromWords(words.value, pass.value);
      },
    }, "Restore from words"),
    h("button", { class: "text", onclick: () => app.go("welcome") }, "Back"),
  );
}
