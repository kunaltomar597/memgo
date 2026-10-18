import { z } from "zod";
import { requireOwnerUser } from "@/lib/auth";
import { getDeps } from "@/lib/deps";
import { json, readJson, route } from "@/lib/http";
import { createShop } from "@/lib/shops";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const body = z.strictObject({
  name: z.string().trim().min(1).max(120),
  city: z.string().trim().min(1).max(80),
  notice_language: z.enum(["en", "hi"]),
});

/** POST /api/shops: create a shop and the caller's owner staff row (TASK-056). */
export const POST = route("shops.create", async (req) => {
  const deps = getDeps();
  const userId = await requireOwnerUser(req, deps.owners);
  const b = await readJson(req, body);
  const id = await createShop(deps.db, { name: b.name, city: b.city, noticeLanguage: b.notice_language, userId });
  return json({ id }, 201);
});
