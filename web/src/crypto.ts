/**
 * Passphrase-encrypted backup envelope, byte-compatible with the Android
 * app's BackupCrypto (KEELBK01): files are interchangeable between the
 * Android app and this PWA.
 *
 * Format: magic "KEELBK01" | saltLen(1) salt | nonceLen(1) nonce |
 * iterations(4 BE) | AES-256-GCM ciphertext (tag appended).
 * KDF: PBKDF2-HMAC-SHA256, 600k iterations, 256-bit key.
 */

const MAGIC = new TextEncoder().encode("KEELBK01");
const ITERATIONS = 600_000;
const SALT_LEN = 16;
const NONCE_LEN = 12;

async function deriveKey(passphrase: string, salt: Uint8Array, iterations: number): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(passphrase) as BufferSource,
    "PBKDF2",
    false,
    ["deriveKey"],
  );
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", salt: salt as BufferSource, iterations, hash: "SHA-256" },
    base,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"],
  );
}

export async function encryptBackup(plaintext: Uint8Array, passphrase: string): Promise<Uint8Array> {
  if (!passphrase) throw new Error("passphrase must not be empty");
  const salt = crypto.getRandomValues(new Uint8Array(SALT_LEN));
  const nonce = crypto.getRandomValues(new Uint8Array(NONCE_LEN));
  const key = await deriveKey(passphrase, salt, ITERATIONS);
  const ct = new Uint8Array(
    await crypto.subtle.encrypt({ name: "AES-GCM", iv: nonce as BufferSource }, key, plaintext as BufferSource),
  );
  const out = new Uint8Array(MAGIC.length + 1 + salt.length + 1 + nonce.length + 4 + ct.length);
  let pos = 0;
  out.set(MAGIC, pos); pos += MAGIC.length;
  out[pos++] = salt.length;
  out.set(salt, pos); pos += salt.length;
  out[pos++] = nonce.length;
  out.set(nonce, pos); pos += nonce.length;
  out[pos++] = (ITERATIONS >>> 24) & 0xff;
  out[pos++] = (ITERATIONS >>> 16) & 0xff;
  out[pos++] = (ITERATIONS >>> 8) & 0xff;
  out[pos++] = ITERATIONS & 0xff;
  out.set(ct, pos);
  return out;
}

export function isKeelBackup(bytes: Uint8Array): boolean {
  if (bytes.length < MAGIC.length) return false;
  return MAGIC.every((b, i) => bytes[i] === b);
}

export async function decryptBackup(envelope: Uint8Array, passphrase: string): Promise<Uint8Array> {
  if (!passphrase) throw new Error("passphrase must not be empty");
  if (!isKeelBackup(envelope)) throw new Error("not a Keel backup file (bad magic)");
  let pos = MAGIC.length;
  const saltLen = envelope[pos++];
  if (envelope.length < pos + saltLen + 1) throw new Error("corrupt backup file");
  const salt = envelope.slice(pos, pos + saltLen); pos += saltLen;
  const nonceLen = envelope[pos++];
  if (envelope.length < pos + nonceLen + 4) throw new Error("corrupt backup file");
  const nonce = envelope.slice(pos, pos + nonceLen); pos += nonceLen;
  const iterations =
    (envelope[pos] << 24) | (envelope[pos + 1] << 16) | (envelope[pos + 2] << 8) | envelope[pos + 3];
  pos += 4;
  if (iterations <= 0) throw new Error("corrupt backup file");
  const ct = envelope.slice(pos);
  if (ct.length === 0) throw new Error("corrupt backup file");
  const key = await deriveKey(passphrase, salt, iterations);
  // AES-GCM tag check fails here on wrong passphrase or tampering.
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: nonce as BufferSource },
    key,
    ct as BufferSource,
  );
  return new Uint8Array(plain);
}

export function toBase64(bytes: Uint8Array): string {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

export function fromBase64(s: string): Uint8Array {
  const bin = atob(s);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
