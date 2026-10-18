import { describe, expect, it } from "vitest";
import { scrubBreadcrumb, scrubEvent, scrubString, sensitiveKey, sentryOptions } from "@/lib/sentry";

// TASK-118 cloud half: the before-send scrubber drops phone numbers, names and any binary.
describe("Sentry before-send scrubber", () => {
  const event = {
    event_id: "abc123",
    level: "error",
    release: "remu-cloud@0.1.0",
    message: "send failed for +919800000001 and 9800000002 and 91 98000 00003",
    exception: {
      values: [
        {
          type: "Error",
          value: "notice to +919800000004 failed",
          stacktrace: { frames: [{ filename: "src/lib/notice.ts", function: "sendPreparedNotice", lineno: 10, vars: { phone: "+919800000005", first_name: "Asha" } }] },
        },
      ],
    },
    request: {
      method: "POST",
      url: "https://remu.example/api/webhooks/whatsapp?hub.verify_token=secret",
      headers: { authorization: "Bearer device-token", "user-agent": "remu-hub/0.1.0", cookie: "a=b" },
      data: { display_name: "Asha Verma", phone_e164: "+919800000006" },
      cookies: { a: "b" },
    },
    user: { id: "u1", email: "owner@example.test", ip_address: "203.0.113.1" },
    extra: {
      display_name: "Asha Verma",
      first_name: "Asha",
      shop_name: "Test Jewellers",
      notes: "likes gold",
      items_text: "Gold necklace",
      phone_hash: "f3a4",
      crop_jpeg_b64: "abc",
      vec_blob: [0.1],
      bytes: new Uint8Array([1, 2, 3]),
      blob: "A".repeat(2000),
      member_id: "m_01JAB3C4D5E6F7G8H9J0K1M2N3",
      count: 3,
      nested: { fcmToken: "x", landmarks: [[1, 2]], ok: "fine" },
    },
    tags: { shop_id: "sharma-jewellers-jaipur" },
    breadcrumbs: [{ category: "fetch", data: { url: "https://graph.facebook.com/v21.0/1/messages?access_token=zzz", body: "{\"to\":\"919800000007\"}" }, message: "to 919800000007" }],
  };

  const out = scrubEvent(event as unknown as Record<string, unknown>);
  const text = JSON.stringify(out);

  it("drops every phone number, name, note, item, token and binary", () => {
    for (const leak of ["9800000001", "9800000002", "98000 00003", "9800000004", "9800000005", "9800000006", "9800000007", "Asha", "Verma", "Test Jewellers", "likes gold", "Gold necklace", "device-token", "owner@example.test", "203.0.113.1", "secret", "zzz", "AAAAAAAAAA", "crop_jpeg_b64", "vec_blob", "fcmToken", "landmarks", "a=b"]) {
      expect(text, leak).not.toContain(leak);
    }
    expect((out.extra as Record<string, unknown>).bytes).toBeUndefined();
  });

  it("keeps ids, structure and stack frames", () => {
    expect(out.event_id).toBe("abc123");
    expect(out.release).toBe("remu-cloud@0.1.0");
    expect((out.extra as Record<string, unknown>).member_id).toBe("m_01JAB3C4D5E6F7G8H9J0K1M2N3");
    expect((out.tags as Record<string, unknown>).shop_id).toBe("sharma-jewellers-jaipur");
    expect(text).toContain("src/lib/notice.ts");
    expect(text).toContain("sendPreparedNotice");
    expect(out.message).toBe("send failed for [phone] and [phone] and [phone]");
    expect((out.request as Record<string, unknown>).headers).toEqual({ "user-agent": "remu-hub/0.1.0" });
    expect(out.user).toEqual({ id: "u1" });
  });

  it("scrubs breadcrumbs and strings", () => {
    const c = scrubBreadcrumb({ message: "call +919800000008", data: { url: "https://x.test/a?b=c", phone: "+91" } });
    expect(JSON.stringify(c)).not.toContain("9800000008");
    expect(JSON.stringify(c)).not.toContain("b=c");
    expect(scrubString("9876543210")).toBe("[phone]");
    expect(sensitiveKey("displayName")).toBe(true);
    expect(sensitiveKey("filename")).toBe(false);
    expect(sensitiveKey("member_id")).toBe(false);
  });

  it("init options: PII off, scrubber wired, release tag", () => {
    const o = sentryOptions("https://key@o0.ingest.sentry.io/1", "remu-cloud@abc");
    expect(o.sendDefaultPii).toBe(false);
    expect(o.release).toBe("remu-cloud@abc");
    expect(JSON.stringify(o.beforeSend({ extra: { phone: "+919800000009" } }))).not.toContain("9800000009");
  });
});
