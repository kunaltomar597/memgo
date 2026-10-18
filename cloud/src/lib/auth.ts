import { createHash, timingSafeEqual } from "node:crypto";
import { jwtVerify } from "jose";
import type { Db } from "./db/types";
import type { Env } from "./env";
import { ApiError, bearer } from "./http";

/** Verifies a Supabase Auth access token server-side and returns the user id, or null. */
export interface OwnerVerifier {
  verify(accessToken: string): Promise<{ userId: string } | null>;
}

/**
 * HS256 with SUPABASE_JWT_SECRET when it is set (legacy JWT secret); otherwise asks Supabase Auth
 * (`auth.getUser`), which also covers projects on asymmetric signing keys.
 */
export function createOwnerVerifier(env: Env): OwnerVerifier {
  if (env.supabaseJwtSecret) {
    const secret = new TextEncoder().encode(env.supabaseJwtSecret);
    return {
      async verify(token) {
        try {
          const { payload } = await jwtVerify(token, secret, { algorithms: ["HS256"], audience: "authenticated" });
          return typeof payload.sub === "string" && payload.sub ? { userId: payload.sub } : null;
        } catch {
          return null;
        }
      },
    };
  }
  return {
    async verify(token) {
      if (!env.supabaseUrl || !env.supabaseAnonKey) return null;
      const { createClient } = await import("@supabase/supabase-js");
      const client = createClient(env.supabaseUrl, env.supabaseAnonKey, {
        auth: { persistSession: false, autoRefreshToken: false },
      });
      const { data, error } = await client.auth.getUser(token);
      if (error || !data.user) return null;
      return { userId: data.user.id };
    },
  };
}

export async function requireOwnerUser(req: Request, verifier: OwnerVerifier): Promise<string> {
  const token = bearer(req);
  if (!token) throw new ApiError(401, "unauthorized", "missing session");
  const user = await verifier.verify(token);
  if (!user) throw new ApiError(401, "unauthorized", "invalid session");
  return user.userId;
}

/** The user must be an owner of the shop (staff.role from the cloud row), else 403. */
export async function requireShopOwner(db: Db, userId: string, shopId: string): Promise<void> {
  const rows = await db.query<{ role: string }>(
    "select role from staff where shop_id = $1 and user_id = $2::uuid and role = 'owner' limit 1",
    [shopId, userId],
  );
  if (rows.length === 0) throw new ApiError(403, "forbidden", "not an owner of this shop");
}

export function sha256Hex(s: string): string {
  return createHash("sha256").update(s, "utf8").digest("hex");
}

export function constantTimeEqual(a: string, b: string): boolean {
  const ab = createHash("sha256").update(a).digest();
  const bb = createHash("sha256").update(b).digest();
  return timingSafeEqual(ab, bb) && a.length === b.length;
}

export interface DeviceRow {
  id: string;
  shop_id: string | null;
  status: "provisioned" | "paired" | "unpaired" | "revoked";
  generation: number;
}

/**
 * Hub calls present the device token; the cloud compares its SHA-256 with devices.token_hash.
 * Revoked devices may still read their inbox (to learn device_revoked) when allowRevoked is set.
 */
export async function requireDevice(
  db: Db,
  req: Request,
  opts: { allowRevoked?: boolean; now: Date },
): Promise<DeviceRow & { shop_id: string }> {
  const token = bearer(req);
  if (!token) throw new ApiError(401, "unauthorized", "missing device token");
  const rows = await db.query<DeviceRow>(
    "select id, shop_id, status, generation from devices where token_hash = $1 limit 1",
    [sha256Hex(token)],
  );
  const d = rows[0];
  if (!d || !d.shop_id || d.status === "unpaired" || d.status === "provisioned") {
    throw new ApiError(401, "unauthorized", "unknown device token");
  }
  if (d.status === "revoked" && !opts.allowRevoked) throw new ApiError(403, "forbidden", "device revoked");
  await db.query("update devices set last_seen_at = $2::timestamptz, updated_at = now() where id = $1", [
    d.id,
    opts.now.toISOString(),
  ]);
  return d as DeviceRow & { shop_id: string };
}

/** Ops routes: X-Remu-Ops-Key must equal OPS_API_KEY. Fails closed when OPS_API_KEY is unset. */
export function requireOpsKey(req: Request, env: Env): void {
  const given = req.headers.get("x-remu-ops-key") ?? "";
  if (!env.opsApiKey || !given || !constantTimeEqual(given, env.opsApiKey)) {
    throw new ApiError(401, "unauthorized", "ops key required");
  }
}
