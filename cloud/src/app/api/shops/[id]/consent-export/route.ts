import { getDeps } from "@/lib/deps";
import { isRateLimited, recordHit } from "@/lib/db/rateLimit";
import { required } from "@/lib/env";
import { fileSha256, loadExport, renderCsv } from "@/lib/export";
import { renderPdf } from "@/lib/exportPdf";
import { ApiError, route } from "@/lib/http";
import { log } from "@/lib/log";
import { ownerOfShop } from "@/lib/owner";
import { loadSigningKey, publicKeyRawBase64, signDetached } from "@/lib/signing";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
export const maxDuration = 30;

/**
 * POST /api/shops/{id}/consent-export?format=csv|pdf (TASK-092, TASK-093). Returns the file with
 * X-Remu-Signature: base64 Ed25519 detached signature over the exact response bytes.
 */
export const POST = route<{ id: string }>("shops.consent_export", async (req, { id }) => {
  const deps = getDeps();
  const userId = await ownerOfShop(req, deps, id);
  const format = new URL(req.url).searchParams.get("format") ?? "csv";
  if (format !== "csv" && format !== "pdf") throw new ApiError(400, "invalid", "format must be csv or pdf");
  const now = deps.now();
  const rlKey = `export:shop:${id}`;
  if (await isRateLimited(deps.db, rlKey, 10, 60, now)) throw new ApiError(429, "rate_limited", "too many exports");
  await recordHit(deps.db, rlKey, now);

  const key = loadSigningKey(required(deps.env.consentExportSigningKey, "CONSENT_EXPORT_SIGNING_KEY"));
  const publicKey = publicKeyRawBase64(key);
  const data = await loadExport(deps.db, id, now);
  const csv = new TextEncoder().encode(renderCsv(data));
  const csvSignature = signDetached(key, csv);

  let bytes: Uint8Array = csv;
  let contentType = "text/csv; charset=utf-8";
  if (format === "pdf") {
    bytes = await renderPdf(data, {
      chainHeadHash: data.chain.head,
      chainVerified: data.chain.ok,
      firstBreak: data.chain.firstBreak ? `${data.chain.firstBreak.id} (${data.chain.firstBreak.reason})` : null,
      latestAnchor: data.anchor ? `${data.anchor.date} ${data.anchor.chain_head_hash}` : "none yet",
      anchorMatchesHead: data.anchorMatchesHead === null ? "no anchor yet" : data.anchorMatchesHead ? "yes" : "NO",
      csvSha256: fileSha256(csv),
      csvSignature,
      publicKey,
    });
    contentType = "application/pdf";
  }
  const signature = format === "csv" ? csvSignature : signDetached(key, bytes);
  const sha = fileSha256(bytes);
  await deps.db.query(
    `insert into export_log (shop_id, requested_by, format, record_count, file_sha256, chain_head_hash, chain_verified)
     values ($1, $2::uuid, $3, $4, $5, $6, $7)`,
    [id, userId, format, data.records.length, sha, data.chain.head, data.chain.ok],
  );
  log.info("consent_export", { shop_id: id, kind: format, count: data.records.length, outcome: data.chain.ok ? "verified" : "chain_break" });
  const stamp = now.toISOString().slice(0, 10);
  return new Response(Buffer.from(bytes), {
    status: 200,
    headers: {
      "content-type": contentType,
      "content-disposition": `attachment; filename="remu-consent-${id}-${stamp}.${format}"`,
      "cache-control": "no-store",
      "x-remu-signature": signature,
      "x-remu-signature-alg": "Ed25519",
      "x-remu-public-key": publicKey,
      "x-remu-sha256": sha,
      "x-remu-chain-head": data.chain.head,
      "x-remu-chain-verified": String(data.chain.ok),
    },
  });
});
