import { readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";
import { canonicalJson } from "@/lib/canonical";
import { computeRecordHash, GENESIS_HASH, orderForChain, verifyChain, type ConsentRecord } from "@/lib/consentChain";
import { phoneHash } from "@/lib/phone";
import { ChainBuilder } from "./helpers/factory";

const vector = JSON.parse(readFileSync(path.join(__dirname, "fixtures", "consent_vector.json"), "utf8")) as {
  phone_hash_vector: { e164: string; phone_hash: string };
  vectors: { record: Omit<ConsentRecord, "record_hash">; canonical: string; expected_record_hash: string }[];
  expected_chain_head: string;
};

describe("canonical JSON and record_hash (API_CONTRACT section 3)", () => {
  it("matches the shared test vector byte for byte", () => {
    for (const v of vector.vectors) {
      expect(canonicalJson(v.record)).toBe(v.canonical);
      expect(computeRecordHash(v.record)).toBe(v.expected_record_hash);
    }
  });

  it("pins the literal hashes the hub team cross-checks", () => {
    expect(vector.vectors[0]!.expected_record_hash).toBe("sha256:7923c819ed1284a0ee0b1e139651718bd7f5349d4d0448dd4512a4aba54413fc");
    expect(vector.vectors[1]!.expected_record_hash).toBe("sha256:ccd16d1fde41d621a78e2c7e490ad81bdc7d0adaab896b218458fccd35d9d0db");
    expect(vector.vectors[1]!.record.prev_hash).toBe(vector.vectors[0]!.expected_record_hash);
  });

  it("keeps non-ASCII as UTF-8, sorts nested keys, omits record_hash", () => {
    expect(canonicalJson({ b: 1, a: { d: null, c: true }, z: "बिल" })).toBe('{"a":{"c":true,"d":null},"b":1,"z":"बिल"}');
    const r = vector.vectors[0]!.record;
    expect(computeRecordHash({ ...r, record_hash: "sha256:whatever" } as ConsentRecord)).toBe(vector.vectors[0]!.expected_record_hash);
  });

  it("refuses floats", () => {
    expect(() => canonicalJson({ amount: 1.5 })).toThrow();
  });

  it("phone_hash contract vector", () => {
    expect(phoneHash("+919876543210")).toBe("f3a47ce5ce3d4ca8ad15225a245b2759022f79489f5c62719b8c9490f7aab90e");
    expect(vector.phone_hash_vector.phone_hash).toBe("f3a47ce5ce3d4ca8ad15225a245b2759022f79489f5c62719b8c9490f7aab90e");
    expect(() => phoneHash("919876543210")).toThrow();
  });
});

describe("verifyChain and orderForChain", () => {
  const vectorChain = vector.vectors.map((v) => ({ ...v.record, record_hash: v.expected_record_hash }) as ConsentRecord);

  it("verifies the vector chain and reports its head", () => {
    const r = verifyChain(vectorChain);
    expect(r).toMatchObject({ ok: true, head: vector.expected_chain_head, count: 2, firstBreak: null });
  });

  it("detects a tampered field and a broken link", () => {
    const tampered = vectorChain.map((r) => ({ ...r }));
    tampered[1]!.bill_no = "INV-CHANGED";
    expect(verifyChain(tampered).firstBreak).toEqual({ id: tampered[1]!.id, reason: "hash" });
    const b = new ChainBuilder("s");
    const a1 = b.next({ member_id: "m_1" });
    b.next({ member_id: "m_2" });
    const a3 = b.next({ member_id: "m_3" });
    expect(verifyChain([a1, a3]).firstBreak).toEqual({ id: a3.id, reason: "link" });
  });

  it("orders an out-of-order batch by prev_hash linkage, then ts and id", () => {
    const b = new ChainBuilder("s");
    const recs = [
      b.next({ member_id: "m_1", ts: "2026-10-18T10:00:00+05:30" }),
      b.next({ member_id: "m_2", ts: "2026-10-18T09:00:00+05:30" }), // hub clock stepped back
      b.next({ member_id: "m_3", ts: "2026-10-18T11:00:00+05:30" }),
    ];
    const shuffled = [recs[2]!, recs[0]!, recs[1]!];
    expect(orderForChain(shuffled, GENESIS_HASH).map((r) => r.id)).toEqual(recs.map((r) => r.id));
  });
});
