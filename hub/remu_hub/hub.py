"""The hub service: one object that owns the database, the gallery and the face engine.

The LAN API, the gate pipeline, the sync loop and the jobs all call into
this class, so every rule (age gate, consent record, gallery refresh,
template deletion) lives in one place.
"""

from __future__ import annotations

import base64
import binascii
import json
import logging
import os
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import cv2
import numpy as np
from numpy.typing import NDArray

from remu_hub import __version__
from remu_hub.clips import ClipRecorder, ClipStore
from remu_hub.db import members as mdb
from remu_hub.db import now_iso
from remu_hub.db import visits as vdb
from remu_hub.db.consent import append_consent_record
from remu_hub.db.database import Database
from remu_hub.events import EventBus
from remu_hub.faces.age import AgeCheck, check_adult
from remu_hub.faces.detect import Detection
from remu_hub.keys import create_shop_key, read_shop_key
from remu_hub.match.decide import Decider, Decision, Kind
from remu_hub.match.gallery import Gallery
from remu_hub.phone import PhoneError, PhoneVault, normalise, phone_hash
from remu_hub.pipeline import FaceEngine
from remu_hub.settings import Settings
from remu_hub.sync import outbox
from remu_hub.video.source import TimedFrame

log = logging.getLogger(__name__)
MAX_CROP_BYTES = 300 * 1024
MAX_FACES = 6
MAX_CROP_PIXELS = 4000 * 4000  # a 300 KB JPEG can still declare a huge image


def jpeg_size(raw: bytes) -> tuple[int, int] | None:
    """Width and height from the JPEG frame header, read before any pixel is decoded."""
    i = 2
    while i + 9 < len(raw):
        if raw[i] != 0xFF:
            return None
        marker = raw[i + 1]
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            i += 2
            continue
        seg = int.from_bytes(raw[i + 2 : i + 4], "big")
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            h = int.from_bytes(raw[i + 5 : i + 7], "big")
            w = int.from_bytes(raw[i + 7 : i + 9], "big")
            return w, h
        i += 2 + seg
    return None


SCRIPT_VERSION = "hi-IN-v1.0"
NOTICE_VERSION = "wa-v1.0"


class EnrolError(Exception):
    def __init__(self, status: int, code: str, detail: str, **extra: Any) -> None:
        super().__init__(detail)
        self.status = status
        self.code = code
        self.detail = detail
        self.extra = extra


@dataclass
class FaceIn:
    crop_jpeg_b64: str
    landmarks: list[list[float]] | None = None
    display_name: str | None = None
    phone: str | None = None


@dataclass
class EnrolIn:
    staff_id: str
    phone: str
    display_name: str = ""
    bill_no: str = ""
    amount: float = 0.0
    items: str = ""
    occasion: str = ""
    notes: str = ""
    household_label: str | None = None
    payer_index: int | None = None
    faces: list[FaceIn] = field(default_factory=list)


class Hub:
    def __init__(
        self,
        settings: Settings,
        *,
        engine: FaceEngine | None = None,
        engine_factory: Callable[[bytes], FaceEngine] | None = None,
        db: Database | None = None,
    ) -> None:
        self.settings = settings
        self.started = time.monotonic()
        settings.data_dir.mkdir(parents=True, exist_ok=True)
        self.db = db or Database(settings.db_path)
        self.shop_key = (
            create_shop_key(settings.shop_key_path)
            if not settings.shop_key_path.exists()
            else read_shop_key(settings.shop_key_path)
        )
        self.vault = PhoneVault(self.shop_key)
        outbox.configure_phone_sealer(
            lambda n: base64.b64encode(self.vault.encrypt(n)).decode(),
            lambda b: self.vault.decrypt(base64.b64decode(b)),
        )
        self._engine = engine
        self._engine_factory = engine_factory
        self._engine_lock = threading.Lock()
        self.gallery = Gallery(settings.match_threshold)
        self.decider = Decider(
            settings.frames_required, settings.frames_window, settings.alert_cooldown_minutes * 60.0
        )
        self.bus = EventBus(self.db)
        self.clips = ClipStore(
            self.db, settings.clips_dir, self.shop_key, settings.clip_retention_days
        )
        self.recorder = ClipRecorder(self.clips)
        self.stream_state = "connecting"
        self.fps = 0.0
        # Revoked by the cloud (device_revoked): persisted, so a restart stays locked (H2).
        self.revoked = self.db.get_kv("revoked") == "1"
        self.data_locked = False
        shop_file = settings.state_dir / "cloud_shop_id"
        self.cloud_shop_id: str | None = (
            (shop_file.read_text().strip() or None) if shop_file.exists() else None
        )
        self.internet = False
        self.ensure_shop()
        self.ensure_pairing_code()
        self.refresh_gallery()

    # ---- setup ---------------------------------------------------------------
    @property
    def engine(self) -> FaceEngine:
        with self._engine_lock:
            if self._engine is None:
                if self._engine_factory is not None:
                    self._engine = self._engine_factory(self.shop_key)
                else:
                    from remu_hub.faces import models
                    from remu_hub.faces.scramble import scrambler_for

                    pack = models.load(self.settings.models_dir, self.settings.model_pack)
                    self._engine = FaceEngine(
                        pack, scrambler_for(self.shop_key), self.settings.min_face_px
                    )
            return self._engine

    def ensure_shop(self) -> None:
        if self.db.one("SELECT 1 FROM shops WHERE id = ?", (self.settings.shop_id,)) is None:
            self.db.execute(
                "INSERT INTO shops (id, shop_key_ref) VALUES (?, ?)",
                (self.settings.shop_id, str(self.settings.shop_key_path)),
            )

    def ensure_pairing_code(self) -> None:
        """A code made at Pi first boot (`init --state-only`) is registered on first start."""
        from remu_hub.db import auth

        code_file = self.settings.state_dir / "pairing.txt"
        if (
            code_file.exists()
            and self.db.one("SELECT 1 FROM pairing_codes WHERE kind = 'hub'") is None
        ):
            self.db.execute(
                "INSERT INTO pairing_codes (code_hash, kind, role, created_at) "
                "VALUES (?, 'hub', 'owner', ?)",
                (auth.sha256(auth.normalise_code(code_file.read_text())), now_iso()),
            )

    def shop(self) -> dict[str, Any]:
        r = self.db.one("SELECT * FROM shops WHERE id = ?", (self.settings.shop_id,))
        assert r is not None
        return {
            "id": r["id"],
            "name": r["name"],
            "script_version": r["script_version"],
            "notice_version": r["notice_version"],
            "settings": json.loads(r["settings_json"]),
        }

    def apply_config(self, cfg: dict[str, Any]) -> None:
        current = self.shop()
        settings = current["settings"] | {"notice_language": cfg.get("notice_language", "hi")}
        self.db.execute(
            "UPDATE shops SET name = ?, script_version = ?, notice_version = ?, "
            "settings_json = ? WHERE id = ?",
            (
                cfg.get("shop_name", current["name"]),
                cfg.get("script_version", current["script_version"]),
                cfg.get("notice_version", current["notice_version"]),
                json.dumps(settings),
                self.settings.shop_id,
            ),
        )

    # ---- gallery ---------------------------------------------------------------
    def seal_secret(self, value: str) -> str:
        from remu_hub.keys import Sealer

        return base64.b64encode(
            Sealer(self.shop_key, b"remu/kv-secret/v1").seal(value.encode())
        ).decode()

    def open_secret(self, sealed: str) -> str:
        from remu_hub.keys import Sealer

        return Sealer(self.shop_key, b"remu/kv-secret/v1").open(base64.b64decode(sealed)).decode()

    @property
    def locked(self) -> bool:
        return self.revoked or self.data_locked

    def revoke(self) -> None:
        """The kill switch: stop matching and every LAN route except /health, across restarts."""
        self.db.set_kv("revoked", "1")
        self.revoked = True
        self.gallery.clear()

    def refresh_gallery(self) -> int:
        """Make the gallery hold exactly the templates of `active` members (empty when locked)."""
        if self.locked:
            self.gallery.clear()
            return 0
        active = dict(mdb.active_templates(self.db))
        for mid in self.gallery.ids():
            if mid not in active:
                self.gallery.remove(mid)
        for mid, vec in active.items():
            self.gallery.replace(mid, vec)
        return len(active)

    # ---- enrolment ---------------------------------------------------------------
    def _decode(self, face: FaceIn, index: int) -> NDArray[np.uint8]:
        try:
            raw = base64.b64decode(face.crop_jpeg_b64, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise EnrolError(400, "invalid", f"face {index}: crop is not base64") from exc
        if len(raw) > MAX_CROP_BYTES:
            raise EnrolError(400, "invalid", f"face {index}: crop is over 300 KB")
        if not raw.startswith(b"\xff\xd8"):
            raise EnrolError(400, "invalid", f"face {index}: crop is not a JPEG")
        size = jpeg_size(raw)
        if size is None or size[0] * size[1] > MAX_CROP_PIXELS:
            raise EnrolError(400, "invalid", f"face {index}: crop is too large or not a JPEG")
        img = cv2.imdecode(np.frombuffer(raw, np.uint8), cv2.IMREAD_COLOR)
        if img is None:
            raise EnrolError(400, "invalid", f"face {index}: crop does not decode")
        return np.asarray(img, np.uint8)

    def _face_detection(self, img: NDArray[np.uint8], face: FaceIn) -> Detection | None:
        dets = self.engine.detect(img, min_face_px=20)
        if dets:
            return dets[0]
        lm = face.landmarks
        if lm is None or len(lm) != 5 or any(len(p) != 2 for p in lm):
            return None
        pts = np.asarray(lm, np.float32)
        span = float(max(pts[:, 0].max() - pts[:, 0].min(), pts[:, 1].max() - pts[:, 1].min()))
        cx, cy = float(pts[:, 0].mean()), float(pts[:, 1].mean())
        half = span * 1.1
        return Detection((cx - half, cy - half, cx + half, cy + half), 0.0, pts)

    def enrol(self, req: EnrolIn) -> dict[str, Any]:
        if self.db.one("SELECT 1 FROM staff WHERE id = ?", (req.staff_id,)) is None:
            raise EnrolError(400, "invalid", "unknown staff_id")
        if len(req.faces) > MAX_FACES:
            raise EnrolError(400, "invalid", f"at most {MAX_FACES} faces")
        try:
            e164 = normalise(req.phone)
            face_phones = [normalise(f.phone) if f.phone else e164 for f in req.faces]
        except PhoneError as exc:
            raise EnrolError(400, "invalid", str(exc)) from exc
        if len(req.faces) >= 2:
            # Each adult's written notice must reach that adult (review M5).
            payer = req.payer_index if req.payer_index is not None else 0
            for i, f in enumerate(req.faces):
                if i != payer and not f.phone:
                    raise EnrolError(
                        422,
                        "phone_required",
                        f"face {i} needs its own WhatsApp number",
                        face_index=i,
                    )
        if req.amount < 0:
            raise EnrolError(400, "invalid", "amount cannot be negative")

        if req.faces:
            from remu_hub.faces.models import ModelError

            try:
                self.engine  # noqa: B018 - load the model pack before decoding crops
            except ModelError as exc:
                raise EnrolError(
                    503,
                    "unavailable",
                    "face models are not installed on the hub " "(run `remu-hub models download`)",
                ) from exc
        # CPU work before taking the database lock.
        accepted: list[tuple[int, NDArray[np.float32]]] = []
        refused: list[dict[str, Any]] = []
        for i, face in enumerate(req.faces):
            img = self._decode(face, i)
            det = self._face_detection(img, face)
            if det is None:
                refused.append({"face_index": i, "reason": "no_face"})
                continue
            verdict, _ = check_adult(self.engine.models.age, img, det.box)
            if verdict is AgeCheck.REFUSED:
                refused.append({"face_index": i, "reason": "under_18"})
                self._count("age_refusals")
                continue  # nothing is embedded or stored for a refused face
            accepted.append((i, self.engine.template(img, det)))
            del img
        if req.faces and not accepted:
            code = "under_18" if any(r["reason"] == "under_18" for r in refused) else "no_face"
            raise EnrolError(422, code, "no face could be enrolled", refused=refused)

        ts = now_iso()
        shop = self.shop()
        with self.db.tx():
            existing = self._members_by_phone(phone_hash(e164))
            with_template = [m for m in existing if m["has_template"]]
            if with_template:
                raise EnrolError(
                    409,
                    "conflict",
                    "this phone already has a member",
                    member_id=with_template[0]["id"],
                )
            phone_only = existing[0]["id"] if existing else None

            if not req.faces:
                mid = phone_only or mdb.insert_member(
                    self.db,
                    shop_id=shop["id"],
                    display_name=req.display_name.strip(),
                    e164=e164,
                    phone_hash=phone_hash(e164),
                    vault=self.vault,
                    ts=ts,
                )
                self._purchase(mid, req, ts)
                if req.notes:
                    self.db.execute("UPDATE members SET notes = ? WHERE id = ?", (req.notes, mid))
                mdb.queue_member(self.db, mid)
                return {
                    "phone_member": True,
                    "household_id": None,
                    "members": [
                        {
                            "face_index": None,
                            "member_id": mid,
                            "status": mdb.PENDING,
                            "consent_record_id": None,
                        }
                    ],
                    "refused": [],
                }

            out_members = []
            payer_idx = req.payer_index if req.payer_index is not None else accepted[0][0]
            if payer_idx not in [i for i, _ in accepted]:
                payer_idx = accepted[0][0]
            for i, template in accepted:
                face = req.faces[i]
                phone_i = face_phones[i]
                name = (face.display_name or (req.display_name if i == payer_idx else "")).strip()
                if phone_only and phone_i == e164 and i == payer_idx:
                    mid = phone_only
                    self.db.execute(
                        "UPDATE members SET display_name = COALESCE(NULLIF(?, ''), "
                        "display_name), updated_at = ? WHERE id = ?",
                        (name, ts, mid),
                    )
                else:
                    mid = mdb.insert_member(
                        self.db,
                        shop_id=shop["id"],
                        display_name=name,
                        e164=phone_i,
                        phone_hash=phone_hash(phone_i),
                        vault=self.vault,
                        ts=ts,
                    )
                mdb.store_template(self.db, mid, template, self.engine.model_version)
                record = append_consent_record(
                    self.db,
                    shop_id=shop["id"],
                    member_id=mid,
                    channel="verbal_at_counter",
                    script_version=shop["script_version"],
                    notice_version=shop["notice_version"],
                    bill_no=req.bill_no or None,
                    cashier_id=req.staff_id,
                    ts=ts,
                )
                if i == payer_idx:
                    self._purchase(mid, req, ts)
                    if req.notes:
                        self.db.execute(
                            "UPDATE members SET notes = ? WHERE id = ?", (req.notes, mid)
                        )
                mdb.queue_member(self.db, mid)
                outbox.enqueue(
                    self.db,
                    "notice_request",
                    {
                        "member_id": mid,
                        "consent_record_id": record["id"],
                        "phone_e164": phone_i,
                        "first_name": name.split(" ")[0] if name else "Member",
                        "shop_name": shop["name"],
                        "language": shop["settings"].get("notice_language", "hi"),
                        "date": ts[:10],
                    },
                )
                out_members.append(
                    {
                        "face_index": i,
                        "member_id": mid,
                        "status": mdb.PENDING,
                        "consent_record_id": record["id"],
                    }
                )
            household_id = None
            if len(out_members) >= 2:
                label = (req.household_label or "").strip() or self._default_label(req)
                payer_member = next(
                    m["member_id"] for m in out_members if m["face_index"] == payer_idx
                )
                household_id = mdb.create_household(
                    self.db, shop["id"], label, [m["member_id"] for m in out_members], payer_member
                )
        for m in out_members:
            self.bus.publish("member_updated", self.member_summary(m["member_id"]))
        return {
            "phone_member": False,
            "household_id": household_id,
            "members": out_members,
            "refused": refused,
        }

    def _default_label(self, req: EnrolIn) -> str:
        parts = req.display_name.strip().split()
        return f"{parts[-1]} family" if parts else "Family"

    def _purchase(self, member_id: str, req: EnrolIn, ts: str) -> None:
        if req.bill_no or req.amount or req.items:
            mdb.add_purchase(
                self.db,
                member_id,
                bill_no=req.bill_no,
                amount=req.amount,
                items=req.items,
                occasion=req.occasion,
                ts=ts,
            )

    def _members_by_phone(self, h: str) -> list[dict[str, Any]]:
        rows = self.db.all(
            "SELECT id, status FROM members WHERE phone_hash = ? AND status IN "
            "('pending_notice', 'active') ORDER BY created_at",
            (h,),
        )
        return [
            {
                "id": r["id"],
                "status": r["status"],
                "has_template": mdb.has_template(self.db, r["id"]),
            }
            for r in rows
        ]

    def _count(self, key: str) -> None:
        self.db.set_kv(key, str(int(self.db.get_kv(key, "0") or 0) + 1))

    # ---- members ---------------------------------------------------------------
    def member_summary(self, member_id: str) -> dict[str, Any]:
        row = self.db.one("SELECT * FROM members WHERE id = ?", (member_id,))
        if row is None:
            raise LookupError(member_id)
        return mdb.summary(self.db, row, self.vault)

    def member_card(self, member_id: str) -> dict[str, Any]:
        row = self.db.one("SELECT * FROM members WHERE id = ?", (member_id,))
        if row is None:
            raise LookupError(member_id)
        card = mdb.summary(self.db, row, self.vault)
        household = None
        if row["household_id"]:
            p = mdb.household_payload(self.db, row["household_id"])
            household = {k: p[k] for k in ("id", "label", "payer_member_id", "member_ids")}
        visits = self.db.one("SELECT COUNT(*) AS n FROM visits WHERE member_id = ?", (member_id,))
        return card | {
            "phone": self.vault.decrypt(row["phone_enc"]),
            "notes": row["notes"],
            "discount_flag": bool(row["discount_flag"]),
            "household": household,
            "purchases": mdb.purchases(self.db, member_id),
            "visits_count": int(visits["n"]) if visits else 0,
        }

    def search(
        self, q: str | None = None, phone: str | None = None, status: str | None = None
    ) -> list[dict[str, Any]]:
        sql, args = "SELECT * FROM members WHERE 1=1", []
        if phone:
            try:
                sql += " AND phone_hash = ?"
                args.append(phone_hash(normalise(phone)))
            except PhoneError:
                return []
        if q:
            sql += " AND display_name LIKE ?"
            args.append(f"%{q.strip()}%")
        if status:
            sql += " AND status = ?"
            args.append(status)
        rows = self.db.all(sql + " ORDER BY updated_at DESC LIMIT 200", args)
        return [mdb.summary(self.db, r, self.vault) for r in rows]

    def patch_member(self, member_id: str, changes: dict[str, Any]) -> dict[str, Any]:
        allowed = {"display_name", "notes"}
        bad = set(changes) - allowed
        if bad:
            raise ValueError(f"cannot change {', '.join(sorted(bad))}")
        with self.db.tx():
            if self.db.one("SELECT 1 FROM members WHERE id = ?", (member_id,)) is None:
                raise LookupError(member_id)
            for k in allowed & set(changes):
                self.db.execute(
                    f"UPDATE members SET {k} = ?, updated_at = ? WHERE id = ?",  # noqa: S608
                    (str(changes[k])[:1000], now_iso(), member_id),
                )
            mdb.queue_member(self.db, member_id)
        summary = self.member_summary(member_id)
        self.bus.publish("member_updated", summary)
        return summary

    def withdraw(self, member_id: str, channel: str, actor: str | None = None) -> dict[str, Any]:
        """STOP, Remove member or an inbox withdrawal: template gone at once, chained record.

        `actor` is the staff id of whoever tapped Remove member; None for STOP and the cloud."""
        result = mdb.transition(self.db, member_id, mdb.WITHDRAWN, channel=channel, actor=actor)
        self.gallery.remove(member_id)
        self.decider.forget_member(member_id)
        self.clips.delete_for_member(member_id)
        self.bus.publish("member_updated", self.member_summary(member_id))
        deleted_at = result.template_deleted_at
        if deleted_at is None:
            last = self.db.one(
                "SELECT template_deleted_at FROM consent_records WHERE member_id"
                " = ? ORDER BY rowid DESC LIMIT 1",
                (member_id,),
            )
            deleted_at = last["template_deleted_at"] if last else None
        return {"status": mdb.WITHDRAWN, "template_deleted_at": deleted_at}

    def activate(self, member_id: str) -> bool:
        """Only the inbox (a delivered notice) calls this. No other path activates a member."""
        row = self.db.one("SELECT status FROM members WHERE id = ?", (member_id,))
        if row is None or row["status"] != mdb.PENDING or not mdb.has_template(self.db, member_id):
            log.info("activation ignored for %s", member_id)
            return False
        # Defence in depth: the hub's own chain must already hold the delivered notice, so a
        # compromised cloud cannot activate a member whose notice never arrived.
        from remu_hub.db.consent import latest_for_member

        last = latest_for_member(self.db, member_id)
        if last is None or last["notice_delivery_json"].get("whatsapp_status") != "delivered":
            self.db.set_kv(f"awaiting_delivery:{member_id}", "1")
            log.info("activation for %s waits for its delivered notice", member_id)
            return False
        self.db.execute("DELETE FROM kv WHERE key = ?", (f"awaiting_delivery:{member_id}",))
        mdb.transition(self.db, member_id, mdb.ACTIVE, channel="whatsapp_webhook")
        self.refresh_gallery()
        self.bus.publish("member_updated", self.member_summary(member_id))
        return True

    # ---- gate decisions ------------------------------------------------------------
    def on_decision(self, decision: Decision, frame: TimedFrame) -> None:
        if self.locked:
            return
        if decision.kind is Kind.SEEN:
            vdb.touch_seen(self.db, decision.member_id)
            return
        prev = self.db.one(
            "SELECT ts FROM visits WHERE member_id = ? ORDER BY ts DESC LIMIT 1",
            (decision.member_id,),
        )
        visit_id = vdb.record_visit(
            self.db,
            decision.member_id,
            score=round(decision.score, 4),
            frames_agreed=decision.frames_agreed,
        )
        self.recorder.start(visit_id, decision.first_ts)
        card = self.member_card(decision.member_id)
        last_purchase = card["purchases"][0] if card["purchases"] else None
        self.bus.publish(
            "alert",
            {
                "visit_id": visit_id,
                "member_id": decision.member_id,
                "display_name": card["display_name"],
                "household_label": card["household_label"],
                "last_visit": prev["ts"] if prev else card["member_since"],
                "last_purchase": (
                    {"amount": last_purchase["amount"], "items": last_purchase["items"]}
                    if last_purchase
                    else None
                ),
                "notes": card["notes"],
                "score": round(decision.score, 4),
                "frames_agreed": decision.frames_agreed,
            },
        )

    # ---- health ---------------------------------------------------------------
    def health(self) -> dict[str, Any]:
        counts = outbox.counts(self.db)
        try:
            model_version = self.engine.model_version
        except Exception:
            model_version = "unavailable"
        return {
            "ok": not self.locked,
            "version": __version__,
            "model_version": model_version,
            "uptime_s": int(time.monotonic() - self.started),
            "gallery_size": len(self.gallery),
            "locked": self.locked,
            "data_dir_encrypted": os.path.ismount(self.settings.data_dir),
            "internet": self.internet,
            "outbox_pending": counts["pending"],
            "outbox_failed": counts["failed"],
            "clock_synced": _clock_synced(),
            "cloud_shop_id": self.cloud_shop_id,
            "fps": round(self.fps, 2),
            "stream": self.stream_state,
        }


def _clock_synced() -> bool:
    from remu_hub.jobs.scheduler import ntp_synchronised

    try:
        return ntp_synchronised()
    except OSError:
        return False
