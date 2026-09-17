import { describe, it, expect } from "vitest";
import { friendlySendError, isUncertainSendError, recentOutboundFound } from "./send-error";

describe("isUncertainSendError", () => {
  it("treats timeouts as uncertain", () => {
    expect(isUncertainSendError("Lightning payment timed out")).toBe(true);
    expect(isUncertainSendError("Ark payment timed out")).toBe(true);
  });
  it("treats real Ark rejections as definite", () => {
    expect(isUncertainSendError("Ark address is for different server")).toBe(false);
    expect(isUncertainSendError("selected inputs don't cover amount")).toBe(false);
    expect(isUncertainSendError("Unknown delivery mechanism: type=0xff")).toBe(false);
  });
});

describe("friendlySendError", () => {
  it("maps server mismatch to the same-server rule", () => {
    expect(friendlySendError("invalid arkoor address: Ark address is for different server")).toMatch(/same Ark server|ark\.signet\.2nd\.dev/);
  });
  it("maps insufficient funds", () => {
    expect(friendlySendError("selected inputs don't cover amount")).toMatch(/Not enough spendable/);
  });
  it("passes through unknown messages", () => {
    expect(friendlySendError("weird bark error")).toBe("weird bark error");
  });
});

describe("recentOutboundFound", () => {
  const since = Date.parse("2026-09-16T07:20:00Z");
  it("matches a recent arkoor send of at least the amount", () => {
    expect(recentOutboundFound(
      [{ subsystemKind: "arkoor", effectiveBalanceSats: -1000, createdAt: "2026-09-16T07:25:00Z" }],
      1000,
      since,
    )).toBe(true);
  });
  it("ignores older movements and receives", () => {
    expect(recentOutboundFound(
      [{ subsystemKind: "arkoor", effectiveBalanceSats: -1000, createdAt: "2026-09-16T07:00:00Z" }],
      1000,
      since,
    )).toBe(false);
    expect(recentOutboundFound(
      [{ subsystemKind: "arkoor", effectiveBalanceSats: 1000, createdAt: "2026-09-16T07:25:00Z" }],
      1000,
      since,
    )).toBe(false);
  });
});
