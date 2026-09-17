/**
 * Payment request parser — Signet-only port of the Android core
 * PaymentRequestParser. Accepts Ark addresses, BOLT11 invoices (amount from
 * the HRP), and Signet on-chain addresses (incl. bitcoin: URIs). Mainnet and
 * regtest destinations are rejected with a clear reason.
 */

export type PaymentKind = "ark" | "lightning" | "onchain" | "unsupported";

export interface ParsedPayment {
  kind: PaymentKind;
  original: string;
  amountSats?: number;
  reason?: string;
}

/** BOLT11 amount from the HRP, e.g. "lntbs1000n" -> 1000? No: 1000n = 100 sats... see multipliers. */
export function bolt11AmountSats(hrp: string): number | undefined {
  const m = /([0-9]+)([munp])?$/.exec(hrp);
  if (!m) return undefined;
  const value = Number(m[1]);
  if (!Number.isSafeInteger(value)) return undefined;
  switch (m[2] ?? "") {
    case "": return value * 100_000_000;
    case "m": return value * 100_000;
    case "u": return value * 100;
    case "n": return Math.floor(value / 10);
    case "p": return Math.floor(value / 1_000);
    default: return undefined;
  }
}

export function parsePayment(rawInput: string): ParsedPayment {
  let input = rawInput.trim();
  if (!input) return { kind: "unsupported", original: rawInput, reason: "empty" };

  // bitcoin: URI wrapper (on-chain)
  let uriAmountSats: number | undefined;
  if (input.toLowerCase().startsWith("bitcoin:")) {
    const withoutScheme = input.slice("bitcoin:".length);
    const q = withoutScheme.indexOf("?");
    const address = q >= 0 ? withoutScheme.slice(0, q) : withoutScheme;
    if (q >= 0) {
      const params = new URLSearchParams(withoutScheme.slice(q + 1));
      const amt = params.get("amount");
      if (amt) {
        const btc = Number(amt);
        if (Number.isFinite(btc) && btc > 0) uriAmountSats = Math.round(btc * 100_000_000);
      }
    }
    input = address;
  }

  const lower = input.toLowerCase();

  // Lightning invoices (regtest prefix must be checked before mainnet "lnbc").
  if (lower.startsWith("lnbcrt")) {
    return { kind: "unsupported", original: input, reason: "That is a regtest invoice; this wallet is on signet." };
  }
  if (lower.startsWith("lntbs") || lower.startsWith("lntb")) {
    return {
      kind: "lightning",
      original: input,
      amountSats: bolt11AmountSats(lower.slice(0, lower.lastIndexOf("1")) || lower),
    };
  }
  if (lower.startsWith("lnbc")) {
    return { kind: "unsupported", original: input, reason: "That is a mainnet invoice; Keel is signet-only." };
  }

  // On-chain addresses.
  if (lower.startsWith("bcrt1")) {
    return { kind: "unsupported", original: input, reason: "That is a regtest address; this wallet is on signet." };
  }
  if (lower.startsWith("tb1") || lower.startsWith("tb1q") || lower.startsWith("tb1p")) {
    return { kind: "onchain", original: input, amountSats: uriAmountSats };
  }
  if (/^[2mn][1-9A-HJ-NP-Za-km-z]{25,34}$/.test(input)) {
    return { kind: "onchain", original: input, amountSats: uriAmountSats };
  }
  if (lower.startsWith("bc1") || lower.startsWith("1") || lower.startsWith("3")) {
    return { kind: "unsupported", original: input, reason: "That is a mainnet address; Keel is signet-only." };
  }

  // Ark addresses: signet bark addresses carry the "tark1" HRP.
  if (lower.startsWith("tark1") || lower.startsWith("ark:") || lower.startsWith("ark1")) {
    if (lower.startsWith("ark1")) {
      return { kind: "unsupported", original: input, reason: "That is a mainnet Ark address; Keel is signet-only." };
    }
    return { kind: "ark", original: input.replace(/^ark:/i, "") };
  }

  return { kind: "unsupported", original: input, reason: "Not an Ark address, Lightning invoice, or signet Bitcoin address." };
}
