/** CoinGecko fiat price, cached. Display only — never used for signing. */

export interface PriceQuote {
  usd: number;
  fetchedAt: number;
}

let cache: PriceQuote | null = null;

export async function fetchPrice(): Promise<PriceQuote | null> {
  try {
    const res = await fetch(
      "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd",
      { signal: AbortSignal.timeout(8000) },
    );
    if (!res.ok) return cache;
    const json = (await res.json()) as { bitcoin?: { usd?: number } };
    const usd = json.bitcoin?.usd;
    if (typeof usd !== "number") return cache;
    cache = { usd, fetchedAt: Date.now() };
    return cache;
  } catch {
    return cache;
  }
}

export function satsToFiat(sats: number, price: PriceQuote | null): string {
  if (!price) return "";
  const usd = (sats / 100_000_000) * price.usd;
  return `≈ $${usd.toLocaleString("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}
