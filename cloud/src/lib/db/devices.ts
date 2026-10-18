import type { Db } from "./types";

export interface DeviceRecord {
  id: string;
  shop_id: string | null;
  serial: string;
  /** Lowercase hex SHA-256 of the normalised pairing code (0008), never the code. */
  pairing_code: string;
  cert_sha256: string | null;
  status: "provisioned" | "paired" | "unpaired" | "revoked";
  generation: number;
  token_hash: string | null;
  token_issued_at: Date | null;
  data_unlock_token_hash: string | null;
  hub_version: string | null;
  last_seen_at: Date | null;
  last_health_at: Date | null;
  locked: boolean;
  chain_break: boolean;
  chain_break_record_id: string | null;
  anchor_mismatch: boolean;
  paired_at: Date | null;
  revoked_at: Date | null;
}

export async function getDevice(db: Db, id: string): Promise<DeviceRecord | undefined> {
  const rows = await db.query<DeviceRecord>("select * from devices where id = $1", [id]);
  return rows[0];
}

/** Looks a device up by pairingCodeHash(code) (devices.ts); the plain code is never stored. */
export async function getDeviceByCode(db: Db, codeHash: string, forUpdate = false): Promise<DeviceRecord | undefined> {
  const rows = await db.query<DeviceRecord>(
    `select * from devices where pairing_code = $1 ${forUpdate ? "for update" : ""}`,
    [codeHash],
  );
  return rows[0];
}

export async function listDevicesForShop(db: Db, shopId: string): Promise<DeviceRecord[]> {
  return db.query<DeviceRecord>("select * from devices where shop_id = $1 and status = 'paired' order by id", [shopId]);
}

export async function listAllDevices(db: Db): Promise<DeviceRecord[]> {
  return db.query<DeviceRecord>("select * from devices order by created_at, id");
}

export async function flagChainBreak(db: Db, deviceId: string, recordId: string, at: Date): Promise<void> {
  await db.query(
    `update devices set chain_break = true,
       chain_break_record_id = coalesce(chain_break_record_id, $2),
       chain_break_at = coalesce(chain_break_at, $3::timestamptz), updated_at = now()
     where id = $1`,
    [deviceId, recordId, at.toISOString()],
  );
}

/** Sticky: a mismatch stays flagged until a person clears it after review. */
export async function flagAnchorMismatch(db: Db, deviceId: string, at: Date): Promise<void> {
  await db.query(
    `update devices set anchor_mismatch = true,
       anchor_mismatch_at = coalesce(anchor_mismatch_at, $2::timestamptz), updated_at = now()
     where id = $1`,
    [deviceId, at.toISOString()],
  );
}
