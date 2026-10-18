import { createPrivateKey, createPublicKey, sign, verify, type KeyObject } from "node:crypto";

/**
 * Ed25519 detached signatures for consent exports. CONSENT_EXPORT_SIGNING_KEY is base64 of either
 * the 32-byte raw seed or the PKCS8 DER private key (48 bytes); a PEM "PRIVATE KEY" block also
 * works. The public key is published with each export so a reader can verify it.
 */
const PKCS8_ED25519_PREFIX = Buffer.from("302e020100300506032b657004220420", "hex");

export function loadSigningKey(value: string): KeyObject {
  const v = value.trim();
  if (v.startsWith("-----BEGIN")) return createPrivateKey(v);
  const bytes = Buffer.from(v, "base64");
  const der = bytes.length === 32 ? Buffer.concat([PKCS8_ED25519_PREFIX, bytes]) : bytes;
  const key = createPrivateKey({ key: der, format: "der", type: "pkcs8" });
  if (key.asymmetricKeyType !== "ed25519") throw new Error("CONSENT_EXPORT_SIGNING_KEY is not an Ed25519 key");
  return key;
}

export function signDetached(key: KeyObject, data: Uint8Array): string {
  return sign(null, data, key).toString("base64");
}

/** Raw 32-byte public key, base64. */
export function publicKeyRawBase64(key: KeyObject): string {
  const spki = createPublicKey(key).export({ format: "der", type: "spki" });
  return Buffer.from(spki.subarray(spki.length - 32)).toString("base64");
}

export function verifyDetached(publicKeyRawB64: string, data: Uint8Array, signatureB64: string): boolean {
  const spki = Buffer.concat([Buffer.from("302a300506032b6570032100", "hex"), Buffer.from(publicKeyRawB64, "base64")]);
  const pub = createPublicKey({ key: spki, format: "der", type: "spki" });
  return verify(null, data, pub, Buffer.from(signatureB64, "base64"));
}
