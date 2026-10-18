import { randomBytes } from "node:crypto";

const CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/** A ULID: 48-bit millisecond time plus 80 random bits, Crockford base32, 26 characters. */
export function ulid(now: number = Date.now()): string {
  let time = "";
  let t = now;
  for (let i = 0; i < 10; i++) {
    time = CROCKFORD[t % 32] + time;
    t = Math.floor(t / 32);
  }
  const rand = randomBytes(16);
  let random = "";
  for (let i = 0; i < 16; i++) random += CROCKFORD[(rand[i] ?? 0) % 32];
  return time + random;
}

export const ULID_RE = /^[0-9A-HJKMNP-TV-Z]{26}$/;

export function prefixedId(prefix: "d" | "s", now?: number): string {
  return `${prefix}_${ulid(now)}`;
}

/** Opaque random token, base64url. */
export function randomToken(bytes = 32): string {
  return randomBytes(bytes).toString("base64url");
}
