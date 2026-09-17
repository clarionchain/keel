/**
 * Vault: stores the passphrase-encrypted mnemonic in IndexedDB.
 * The browser has no Keystore; at-rest protection is the user's passphrase
 * (KEELBK01 envelope via WebCrypto). The wallet's own state is persisted by
 * the Bark SDK in its own IndexedDB database ("keel-signet").
 */

const DB = "keel-vault";
const STORE = "vault";
const KEY = "mnemonic";

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function vaultSave(envelopeB64: string): Promise<void> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).put(envelopeB64, KEY);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

export async function vaultLoad(): Promise<string | null> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readonly");
    const req = tx.objectStore(STORE).get(KEY);
    req.onsuccess = () => resolve((req.result as string) ?? null);
    req.onerror = () => reject(req.error);
  });
}

export async function vaultClear(): Promise<void> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).delete(KEY);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
  // Note: the Bark wallet database is deleted separately (wallet.close + deleteDatabase).
}

export async function vaultDeleteWalletDb(dbName: string): Promise<void> {
  const names = new Set<string>([dbName]);
  // The SDK may create auxiliary databases (onchain persister etc.); wipe all keel-* dbs.
  if (typeof indexedDB.databases === "function") {
    try {
      for (const db of await indexedDB.databases()) {
        if (db.name && db.name.startsWith("keel")) names.add(db.name);
      }
    } catch {
      // best effort
    }
  }
  await Promise.all(
    [...names].map(
      (name) =>
        new Promise<void>((resolve) => {
          const req = indexedDB.deleteDatabase(name);
          req.onsuccess = () => resolve();
          req.onerror = () => resolve();
          req.onblocked = () => resolve();
        }),
    ),
  );
}
