import { describe, it, expect } from "vitest";
import { webcrypto } from "node:crypto";
import { encryptBackup, decryptBackup, isKeelBackup, toBase64, fromBase64 } from "./crypto";

// Node 18 has WebCrypto but not as a global; the browser provides it natively.
if (!globalThis.crypto) {
  Object.defineProperty(globalThis, "crypto", { value: webcrypto });
}

const PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

describe("KEELBK01 backup envelope", () => {
  it("round-trips", async () => {
    const plain = new TextEncoder().encode(PHRASE);
    const envelope = await encryptBackup(plain, "correct horse battery staple");
    expect(isKeelBackup(envelope)).toBe(true);
    const back = await decryptBackup(envelope, "correct horse battery staple");
    expect(new TextDecoder().decode(back)).toBe(PHRASE);
  });

  it("starts with the KEELBK01 magic", async () => {
    const envelope = await encryptBackup(new TextEncoder().encode(PHRASE), "passphrase");
    expect(new TextDecoder().decode(envelope.slice(0, 8))).toBe("KEELBK01");
  });

  it("rejects a wrong passphrase", async () => {
    const envelope = await encryptBackup(new TextEncoder().encode(PHRASE), "right");
    await expect(decryptBackup(envelope, "wrong")).rejects.toThrow();
  });

  it("detects tampering", async () => {
    const envelope = await encryptBackup(new TextEncoder().encode(PHRASE), "passphrase");
    envelope[envelope.length - 1] ^= 0xff;
    await expect(decryptBackup(envelope, "passphrase")).rejects.toThrow();
  });

  it("rejects bad magic", async () => {
    const junk = new TextEncoder().encode("NOTABACKUPFILE");
    expect(isKeelBackup(junk)).toBe(false);
    await expect(decryptBackup(junk, "passphrase")).rejects.toThrow(/magic/);
  });

  it("rejects empty passphrase", async () => {
    await expect(encryptBackup(new Uint8Array([1]), "")).rejects.toThrow(/empty/);
  });

  it("base64 round-trips", () => {
    const bytes = crypto.getRandomValues(new Uint8Array(64));
    expect(fromBase64(toBase64(bytes))).toEqual(bytes);
  });
});
