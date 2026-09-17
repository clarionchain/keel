/** Send-failure classification and plain-language mapping. */

export interface HistoryLike {
  effectiveBalanceSats?: number;
  intendedBalanceSats?: number;
  subsystemKind?: string;
  subsystemName?: string;
  createdAt: string;
}

/** Timeouts and dropped connections — the send may still complete on sync. */
export function isUncertainSendError(msg: string): boolean {
  return /timed? ?out|deadline exceeded|unavailable|failed to fetch|networkerror|connection/i.test(msg);
}

export function friendlySendError(msg: string): string {
  if (/different server|invalid ark server/i.test(msg)) {
    return "This address belongs to a different Ark server. Keel pays through ark.signet.2nd.dev — the destination wallet must too.";
  }
  if (/different network|network mismatch/i.test(msg)) {
    return "That address is for a different Bitcoin network.";
  }
  if (/unknown delivery/i.test(msg)) {
    return "Keel can't deliver to this Ark address.";
  }
  if (/insufficient|don't cover amount|not enough/i.test(msg)) {
    return "Not enough spendable sats for this send (including the fee).";
  }
  if (/invalid arkoor|failed signet validation|invalid ark address/i.test(msg)) {
    return "This Ark address isn't payable from Keel.";
  }
  if (/unknown payment/i.test(msg)) {
    return "The Ark server lost track of an old payment record. Your funds are safe. If this stays, restore from your seed backup to resync.";
  }
  if (/before wallet birthday|created after the tip/i.test(msg)) {
    return "The Ark test server's chain is out of sync (server-side issue, not your wallet). Funds are safe — try again later.";
  }
  return msg;
}

/** True when history already has a matching outbound since `sinceMs`. */
export function recentOutboundFound(
  history: HistoryLike[],
  amountSats: number,
  sinceMs: number,
): boolean {
  return history.some((m) => {
    const amt = m.effectiveBalanceSats || m.intendedBalanceSats || 0;
    const kind = `${m.subsystemKind ?? ""} ${m.subsystemName ?? ""}`;
    const created = Date.parse(m.createdAt);
    if (!Number.isFinite(created) || created < sinceMs) return false;
    if (!/arkoor|lightning|offboard/i.test(kind)) return false;
    return amt < 0 && Math.abs(amt) >= amountSats;
  });
}
