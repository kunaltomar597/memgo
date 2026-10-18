import { createHmac } from "node:crypto";
import { readFileSync } from "node:fs";
import path from "node:path";
import * as webhookRoute from "@/app/api/webhooks/whatsapp/route";
import { call } from "./http";

const FIXTURES = path.resolve(__dirname, "..", "fixtures", "webhooks");

/** A recorded-style webhook fixture with {{placeholders}} filled in. */
export function fixture(name: string, vars: Record<string, string>): string {
  let text = readFileSync(path.join(FIXTURES, `${name}.json`), "utf8");
  for (const [k, v] of Object.entries(vars)) text = text.split(`{{${k}}}`).join(v);
  if (/\{\{\w+\}\}/.test(text)) throw new Error(`unfilled placeholder in ${name}`);
  return text;
}

export function sign(raw: string, secret: string): string {
  return `sha256=${createHmac("sha256", secret).update(raw, "utf8").digest("hex")}`;
}

export async function postWebhook(raw: string, secret: string, signature?: string) {
  return call(webhookRoute, "POST", {
    path: "/api/webhooks/whatsapp",
    raw,
    headers: { "content-type": "application/json", "x-hub-signature-256": signature ?? sign(raw, secret) },
  });
}

export function unix(iso: string): string {
  return String(Math.floor(new Date(iso).getTime() / 1000));
}
