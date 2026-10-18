/**
 * Canonical JSON for consent record hashing (docs/API_CONTRACT.md section 3): keys sorted at every
 * level by code point, no whitespace, non-ASCII kept as UTF-8, booleans and null as literals.
 * Equivalent to Python json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).
 * No record field is a float; a non-integer number is refused rather than risk a formatting split.
 */
export type Json = null | boolean | number | string | Json[] | { [k: string]: Json };

function compareCodePoints(a: string, b: string): number {
  const ai = Array.from(a);
  const bi = Array.from(b);
  const n = Math.min(ai.length, bi.length);
  for (let i = 0; i < n; i++) {
    const x = ai[i]!.codePointAt(0)!;
    const y = bi[i]!.codePointAt(0)!;
    if (x !== y) return x - y;
  }
  return ai.length - bi.length;
}

function sortDeep(v: unknown): Json {
  if (v === null) return null;
  if (Array.isArray(v)) return v.map(sortDeep);
  switch (typeof v) {
    case "boolean":
    case "string":
      return v;
    case "number":
      if (!Number.isInteger(v)) throw new Error("canonical JSON refuses non-integer numbers");
      return v;
    case "object": {
      const out: { [k: string]: Json } = {};
      for (const k of Object.keys(v as object).sort(compareCodePoints)) {
        const child = (v as Record<string, unknown>)[k];
        if (child === undefined) continue;
        out[k] = sortDeep(child);
      }
      return out;
    }
    default:
      throw new Error(`canonical JSON refuses ${typeof v}`);
  }
}

export function canonicalJson(v: unknown): string {
  return JSON.stringify(sortDeep(v));
}
