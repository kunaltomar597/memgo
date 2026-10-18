-- Remu hub schema v1. Columns follow docs/BRIEF.md section 10 exactly; hub-only
-- helper tables (app_tokens, pairing_codes, kv, ws_events) are marked.

CREATE TABLE shops (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL DEFAULT '',
  city TEXT NOT NULL DEFAULT '',
  shop_key_ref TEXT NOT NULL,
  script_version TEXT NOT NULL DEFAULT 'hi-IN-v1.0',
  notice_version TEXT NOT NULL DEFAULT 'wa-v1.0',
  settings_json TEXT NOT NULL DEFAULT '{"notice_language":"hi"}'
);

CREATE TABLE staff (
  id TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL REFERENCES shops(id),
  name TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('owner', 'cashier')),
  pin_hash TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE households (
  id TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL REFERENCES shops(id),
  label TEXT NOT NULL,
  payer_member_id TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE members (
  id TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL REFERENCES shops(id),
  household_id TEXT REFERENCES households(id),
  display_name TEXT NOT NULL DEFAULT '',
  phone_hash TEXT NOT NULL,
  phone_enc BLOB NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending_notice', 'active', 'withdrawn', 'expired')),
  member_since TEXT NOT NULL,
  last_seen_at TEXT,
  discount_flag INTEGER NOT NULL DEFAULT 1,
  notes TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX members_phone ON members(phone_hash);
CREATE INDEX members_status ON members(status);

CREATE TABLE templates (
  id TEXT PRIMARY KEY,
  member_id TEXT NOT NULL UNIQUE REFERENCES members(id),
  vec_blob BLOB NOT NULL,
  model_version TEXT NOT NULL,
  created_at TEXT NOT NULL
);

CREATE TABLE consent_records (
  id TEXT PRIMARY KEY,
  shop_id TEXT NOT NULL,
  member_id TEXT NOT NULL,
  bill_no TEXT,
  ts TEXT NOT NULL,
  cashier_id TEXT,
  channel TEXT NOT NULL,
  script_version TEXT NOT NULL,
  notice_version TEXT NOT NULL,
  purposes_json TEXT NOT NULL,
  notice_delivery_json TEXT NOT NULL,
  adult_check TEXT NOT NULL,
  withdrawn_at TEXT,
  template_deleted_at TEXT,
  prev_hash TEXT NOT NULL,
  record_hash TEXT NOT NULL UNIQUE
);
CREATE INDEX consent_member ON consent_records(member_id);

CREATE TRIGGER consent_records_no_update BEFORE UPDATE ON consent_records
BEGIN SELECT RAISE(ABORT, 'consent_records is append-only'); END;
CREATE TRIGGER consent_records_no_delete BEFORE DELETE ON consent_records
BEGIN SELECT RAISE(ABORT, 'consent_records is append-only'); END;

CREATE TABLE purchases (
  id TEXT PRIMARY KEY,
  member_id TEXT NOT NULL REFERENCES members(id),
  bill_no TEXT NOT NULL DEFAULT '',
  amount REAL NOT NULL DEFAULT 0,
  items_text TEXT NOT NULL DEFAULT '',
  occasion TEXT NOT NULL DEFAULT '',
  ts TEXT NOT NULL
);
CREATE INDEX purchases_member ON purchases(member_id);

CREATE TABLE visits (
  id TEXT PRIMARY KEY,
  member_id TEXT NOT NULL REFERENCES members(id),
  ts TEXT NOT NULL,
  camera TEXT NOT NULL DEFAULT 'gate',
  score REAL NOT NULL,
  frames_agreed INTEGER NOT NULL,
  clip_id TEXT,
  greeted INTEGER NOT NULL DEFAULT 0,
  greeted_by TEXT,
  not_them INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX visits_member ON visits(member_id, ts);

CREATE TABLE clips (
  id TEXT PRIMARY KEY,
  visit_id TEXT NOT NULL REFERENCES visits(id),
  path_enc TEXT NOT NULL,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

CREATE TABLE events_outbox (
  id TEXT PRIMARY KEY,
  type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  synced_at TEXT,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'synced', 'failed')),
  failure_reason TEXT
);
CREATE INDEX outbox_pending ON events_outbox(status, id);

-- Hub-only helper tables.
CREATE TABLE app_tokens (
  token_hash TEXT PRIMARY KEY,
  role TEXT NOT NULL CHECK (role IN ('owner', 'counter')),
  created_at TEXT NOT NULL,
  revoked_at TEXT
);

CREATE TABLE pairing_codes (
  code_hash TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('hub', 'invite')),
  role TEXT NOT NULL CHECK (role IN ('owner', 'counter')),
  created_at TEXT NOT NULL,
  expires_at TEXT,
  used_at TEXT
);

CREATE TABLE kv (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);

CREATE TABLE ws_events (
  id TEXT PRIMARY KEY,
  type TEXT NOT NULL,
  ts TEXT NOT NULL,
  payload_json TEXT NOT NULL
);
