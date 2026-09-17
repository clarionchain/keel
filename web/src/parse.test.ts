import { describe, it, expect } from "vitest";
import { parsePayment, bolt11AmountSats } from "./parse";

describe("bolt11AmountSats", () => {
  it("parses no-suffix HRP as whole BTC", () => {
    expect(bolt11AmountSats("lntbs1")).toBe(100_000_000);
  });
  it("parses milli", () => {
    expect(bolt11AmountSats("lntbs10m")).toBe(1_000_000);
  });
  it("parses micro", () => {
    expect(bolt11AmountSats("lntbs100u")).toBe(10_000);
  });
  it("parses nano with floor", () => {
    expect(bolt11AmountSats("lntbs15n")).toBe(1);
  });
  it("parses pico with floor", () => {
    expect(bolt11AmountSats("lntbs1500p")).toBe(1);
  });
});

describe("parsePayment", () => {
  it("accepts signet Ark addresses", () => {
    const p = parsePayment("tark1qqsomething");
    expect(p.kind).toBe("ark");
  });
  it("rejects mainnet Ark addresses", () => {
    expect(parsePayment("ark1qqsomething").kind).toBe("unsupported");
  });
  it("accepts signet lightning invoices with amount", () => {
    const p = parsePayment("LNTBS100u1pabcdef");
    expect(p.kind).toBe("lightning");
    expect(p.amountSats).toBe(10_000);
  });
  it("rejects mainnet invoices", () => {
    const p = parsePayment("lnbc100u1pabcdef");
    expect(p.kind).toBe("unsupported");
    expect(p.reason).toMatch(/mainnet/);
  });
  it("rejects regtest invoices", () => {
    const p = parsePayment("lnbcrt100u1pabcdef");
    expect(p.kind).toBe("unsupported");
    expect(p.reason).toMatch(/regtest/);
  });
  it("accepts signet bech32 addresses", () => {
    expect(parsePayment("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx").kind).toBe("onchain");
  });
  it("accepts legacy testnet addresses", () => {
    expect(parsePayment("mipcBbFg9gMiCh81Kj8tqqdgoZub1ZJRfn").kind).toBe("onchain");
  });
  it("rejects mainnet bech32", () => {
    expect(parsePayment("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4").kind).toBe("unsupported");
  });
  it("handles bitcoin: URIs with amount", () => {
    const p = parsePayment("bitcoin:tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx?amount=0.0001");
    expect(p.kind).toBe("onchain");
    expect(p.amountSats).toBe(10_000);
  });
  it("rejects garbage", () => {
    expect(parsePayment("hello world").kind).toBe("unsupported");
  });
  it("rejects empty input", () => {
    expect(parsePayment("   ").kind).toBe("unsupported");
  });
});
