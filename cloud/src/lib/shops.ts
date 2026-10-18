import { randomBytes } from "node:crypto";
import type { Db } from "./db/types";
import { ApiError } from "./http";
import { prefixedId } from "./ids";
import { log } from "./log";

export const SCRIPT_VERSION = "hi-IN-v1.0";
export const NOTICE_VERSION = "wa-v1.0";

export type NoticeLanguage = "en" | "hi";

export interface ShopRow {
  id: string;
  name: string;
  city: string;
  script_version: string;
  notice_version: string;
  settings_json: { notice_language?: NoticeLanguage } & Record<string, unknown>;
}

/** Readable shop ids like the brief's "sharma-jewellers-jaipur"; ASCII only. */
export function slugify(name: string, city: string): string {
  const ascii = (v: string) =>
    v
      .normalize("NFKD")
      .replace(/[^\x00-\x7f]/g, "")
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "-")
      .replace(/^-+|-+$/g, "");
  // A name in Devanagari has no ASCII slug: use a random stem so shops in one city do not collide.
  const namePart = ascii(name).length >= 3 ? ascii(name) : `shop-${randomBytes(3).toString("hex")}`;
  return [namePart, ascii(city)].filter(Boolean).join("-").slice(0, 60).replace(/-+$/g, "");
}

/** POST /api/shops: the shop plus the signed-in user's owner staff row, in one transaction. */
export async function createShop(
  db: Db,
  args: { name: string; city: string; noticeLanguage: NoticeLanguage; userId: string },
): Promise<string> {
  const base = slugify(args.name, args.city);
  return db.tx(async (tx) => {
    let id = base;
    for (let attempt = 0; attempt < 5; attempt++) {
      const rows = await tx.query<{ id: string }>(
        `insert into shops (id, name, city, script_version, notice_version, settings_json, created_by)
         values ($1, $2, $3, $4, $5, $6::jsonb, $7::uuid) on conflict (id) do nothing returning id`,
        [id, args.name, args.city, SCRIPT_VERSION, NOTICE_VERSION, JSON.stringify({ notice_language: args.noticeLanguage }), args.userId],
      );
      if (rows.length === 1) {
        await tx.query(
          "insert into staff (id, shop_id, name, role, user_id) values ($1, $2, 'Owner', 'owner', $3::uuid)",
          [prefixedId("s"), id, args.userId],
        );
        log.info("shop_created", { shop_id: id });
        return id;
      }
      id = `${base.slice(0, 54)}-${randomBytes(2).toString("hex")}`;
    }
    throw new ApiError(409, "conflict", "could not allocate a shop id");
  });
}

export async function getShop(db: Db, id: string): Promise<ShopRow | undefined> {
  const rows = await db.query<ShopRow>(
    "select id, name, city, script_version, notice_version, settings_json from shops where id = $1",
    [id],
  );
  return rows[0];
}

export function noticeLanguageOf(shop: ShopRow | undefined, fallback: NoticeLanguage): NoticeLanguage {
  const l = shop?.settings_json?.notice_language;
  return l === "en" || l === "hi" ? l : fallback;
}

/** The `config` inbox message for the hub. Never carries staff rows or matching tunables. */
export async function configPayload(db: Db, shopId: string) {
  const shop = await getShop(db, shopId);
  if (!shop) throw new ApiError(404, "not_found", "no such shop");
  return {
    shop_name: shop.name,
    notice_language: noticeLanguageOf(shop, "hi"),
    script_version: shop.script_version,
    notice_version: shop.notice_version,
  };
}
