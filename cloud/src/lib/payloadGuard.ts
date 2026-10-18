import { ISO_TS_RE } from "./time";
/**
 * The payload_forbidden rule (docs/API_CONTRACT.md section 2): the cloud never accepts image
 * bytes, templates or clips. A payload is refused when any key, at any depth, contains vec,
 * template, image, crop, clip, jpeg or embedding (the boolean has_template is the one exception),
 * or when any string over 1,024 characters decodes as base64.
 * Contract ambiguity resolved: the consent record column template_deleted_at (section 3) contains
 * "template", so it is exempt by exact name when its value is null or an ISO timestamp.
 */

const FORBIDDEN_KEY_PARTS = ["vec", "template", "image", "crop", "clip", "jpeg", "embedding"] as const;
const BASE64_RE = /^[A-Za-z0-9+/_-]+={0,2}$/;
export const MAX_PLAIN_STRING = 1024;

export interface Violation {
  path: string;
  reason: "forbidden_key" | "base64_blob";
}

export function looksLikeBase64Blob(s: string): boolean {
  if (s.length <= MAX_PLAIN_STRING) return false;
  const compact = s.replace(/\s+/g, "");
  if (!BASE64_RE.test(compact)) return false;
  const len = compact.replace(/=+$/, "").length;
  return len % 4 !== 1; // a length of 4n+1 cannot be base64
}

function keyForbidden(key: string, value: unknown): boolean {
  const k = key.toLowerCase();
  if (k === "has_template" && typeof value === "boolean") return false;
  if (k === "template_deleted_at" && (value === null || (typeof value === "string" && ISO_TS_RE.test(value)))) return false;
  return FORBIDDEN_KEY_PARTS.some((p) => k.includes(p));
}

export function findForbidden(value: unknown, path = "$"): Violation | null {
  if (typeof value === "string") {
    return looksLikeBase64Blob(value) ? { path, reason: "base64_blob" } : null;
  }
  if (Array.isArray(value)) {
    for (let i = 0; i < value.length; i++) {
      const v = findForbidden(value[i], `${path}[${i}]`);
      if (v) return v;
    }
    return null;
  }
  if (value && typeof value === "object") {
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
      if (keyForbidden(k, v)) return { path: `${path}.${k}`, reason: "forbidden_key" };
      const inner = findForbidden(v, `${path}.${k}`);
      if (inner) return inner;
    }
  }
  return null;
}
