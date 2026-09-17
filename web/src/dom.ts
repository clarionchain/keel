/** Minimal DOM builder. */
export function h(
  tag: string,
  attrs: Record<string, string | boolean | ((ev: Event) => void)> = {},
  ...children: (Node | string | null | undefined)[]
): HTMLElement {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (typeof v === "function") {
      el.addEventListener(k.slice(2).toLowerCase(), v as EventListener);
    } else if (typeof v === "boolean") {
      if (v) el.setAttribute(k, "");
    } else {
      el.setAttribute(k, v);
    }
  }
  for (const c of children) {
    if (c == null) continue;
    el.append(c);
  }
  return el;
}

export function fmtSats(v: number): string {
  return v.toLocaleString("en-US");
}

export function fmtTime(epochMs: number): string {
  const d = new Date(epochMs);
  return d.toLocaleString("en-US", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
}

export function shortAddr(a: string): string {
  return a.length <= 24 ? a : `${a.slice(0, 12)}…${a.slice(-8)}`;
}
