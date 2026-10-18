"""The hub LAN API (docs/API_CONTRACT.md section 1)."""

import asyncio
import contextlib
import ipaddress
import json
import time
from collections import defaultdict, deque
from pathlib import Path
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, WebSocket
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, PlainTextResponse, Response
from pydantic import BaseModel, ConfigDict, Field
from starlette.websockets import WebSocketDisconnect

from remu_hub.api.tls import fingerprint
from remu_hub.db import auth
from remu_hub.db import members as mdb
from remu_hub.db import visits as vdb
from remu_hub.hub import EnrolError, EnrolIn, FaceIn, Hub

PING_S = 20.0
MAX_BODY_BYTES = 2_600_000  # six 300 KB crops in base64 plus the form


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class PairIn(Strict):
    code: str = Field(min_length=4, max_length=20)


class StaffIn(Strict):
    name: str = Field(min_length=1, max_length=80)
    role: str
    pin: str


class PinIn(Strict):
    pin: str


class FaceModel(Strict):
    crop_jpeg_b64: str = Field(max_length=420_000)
    landmarks: list[list[float]] | None = None
    display_name: str | None = Field(default=None, max_length=80)
    phone: str | None = Field(default=None, max_length=20)


class EnrolModel(Strict):
    staff_id: str | None = None
    phone: str = Field(max_length=20)
    display_name: str = Field(default="", max_length=80)
    bill_no: str = Field(default="", max_length=60)
    amount: float = Field(default=0.0, ge=0, le=100_000_000)
    items: str = Field(default="", max_length=500)
    occasion: str = Field(default="", max_length=200)
    notes: str = Field(default="", max_length=1000)
    household_label: str | None = Field(default=None, max_length=80)
    payer_index: int | None = Field(default=None, ge=0, le=5)
    faces: list[FaceModel] = Field(default_factory=list, max_length=6)


class RemoveIn(Strict):
    staff_id: str | None = None


class PurchaseIn(Strict):
    staff_id: str | None = None
    bill_no: str = Field(default="", max_length=60)
    amount: float = Field(default=0.0, ge=0, le=100_000_000)
    items: str = Field(default="", max_length=500)
    occasion: str = Field(default="", max_length=200)


class HouseholdIn(Strict):
    label: str = Field(min_length=1, max_length=80)
    member_ids: list[str] = Field(min_length=1, max_length=6)
    payer_member_id: str | None = None


class HouseholdPatch(Strict):
    label: str | None = Field(default=None, min_length=1, max_length=80)
    payer_member_id: str | None = None
    add_member_ids: list[str] = Field(default_factory=list)
    remove_member_ids: list[str] = Field(default_factory=list)


class DiscoverIn(Strict):
    user: str = Field(default="admin", max_length=64)
    password: str = Field(default="", max_length=128)
    subnet: str | None = Field(default=None, max_length=32)


class StreamsIn(Strict):
    gate_url: str = Field(min_length=8, max_length=500)


class VisitPatch(Strict):
    greeted: bool | None = None
    greeted_by: str | None = Field(default=None, pattern=r"^s_[0-9A-HJKMNP-TV-Z]{26}$")
    not_them: bool | None = None


def error(status: int, code: str, detail: str, **extra: Any) -> JSONResponse:
    return JSONResponse({"error": code, "detail": detail, **extra}, status_code=status)


def is_lan(host: str | None) -> bool:
    if host is None:
        return False
    if host == "testclient":
        return True
    try:
        ip = ipaddress.ip_address(host)
    except ValueError:
        return False
    return ip.is_private or ip.is_loopback or ip.is_link_local


class RateLimiter:
    def __init__(self, limit: int, window_s: float) -> None:
        self.limit = limit
        self.window = window_s
        self.hits: dict[str, deque[float]] = defaultdict(deque)

    def blocked(self, key: str) -> bool:
        if len(self.hits) > 10_000:  # bounded memory: forget keys with no recent hits
            now = time.monotonic()
            for k in [k for k, v in self.hits.items() if not v or now - v[-1] > self.window]:
                del self.hits[k]
        q = self.hits[key]
        now = time.monotonic()
        while q and now - q[0] > self.window:
            q.popleft()
        return len(q) >= self.limit

    def hit(self, key: str) -> None:
        self.hits[key].append(time.monotonic())


def create_app(hub: Hub, cert_path: Path | None = None) -> FastAPI:
    app = FastAPI(title="Remu hub", version="0.1.0", docs_url=None, redoc_url=None)
    pair_limiter = RateLimiter(5, 60.0)
    shop_limiter = RateLimiter(20, 60.0)  # wrong codes or PINs per minute for the whole hub
    cert_fp = fingerprint(cert_path) if cert_path and cert_path.exists() else ""

    def role(authorization: Annotated[str | None, Header()] = None) -> str:
        if not authorization or not authorization.startswith("Bearer "):
            raise HTTPException(401, "unauthorized")
        r = auth.token_role(hub.db, authorization[7:].strip())
        if r is None:
            raise HTTPException(401, "unauthorized")
        return r

    def owner(r: Annotated[str, Depends(role)]) -> str:
        if r != "owner":
            raise HTTPException(403, "forbidden")
        return r

    token_limiter = RateLimiter(120, 60.0)

    def limited(
        r: Annotated[str, Depends(role)], authorization: Annotated[str | None, Header()] = None
    ) -> str:
        key = "tok:" + auth.sha256(authorization or "")
        if token_limiter.blocked(key):
            raise HTTPException(429, "too many requests for this token")
        token_limiter.hit(key)
        return r

    Role = Annotated[str, Depends(limited)]
    Owner = Annotated[str, Depends(owner)]
    sessions = auth.StaffSessions()

    def staff(r: Role, x_remu_staff_session: Annotated[str | None, Header()] = None) -> str:
        """The staff id behind a PIN-verified session (security review M1)."""
        del r
        sid = sessions.staff_for(x_remu_staff_session)
        if sid is None or hub.db.one("SELECT 1 FROM staff WHERE id = ?", (sid,)) is None:
            raise HTTPException(401, "staff_session_required")
        return sid

    Staff = Annotated[str, Depends(staff)]

    @app.exception_handler(HTTPException)
    async def http_error(_: Request, exc: HTTPException) -> JSONResponse:
        if exc.detail == "staff_session_required":
            return error(401, "staff_session_required", "enter your PIN again")
        code = {
            401: "unauthorized",
            403: "forbidden",
            404: "not_found",
            409: "conflict",
            429: "rate_limited",
        }.get(exc.status_code, "invalid")
        return error(exc.status_code, code, str(exc.detail))

    @app.exception_handler(RequestValidationError)
    async def invalid(_: Request, exc: RequestValidationError) -> JSONResponse:
        first = exc.errors()[0] if exc.errors() else {}
        where = ".".join(str(p) for p in first.get("loc", ()) if p != "body")
        return error(400, "invalid", f"{where}: {first.get('msg', 'invalid request')}")

    @app.exception_handler(LookupError)
    async def not_found(_: Request, exc: LookupError) -> JSONResponse:
        return error(404, "not_found", "not found")

    @app.middleware("http")
    async def headers(request: Request, call_next: Any) -> Response:
        length = request.headers.get("content-length")
        if length is not None and (not length.isdigit() or int(length) > MAX_BODY_BYTES):
            return error(413, "invalid", "request body too large")
        if hub.locked and request.url.path != "/health":
            response: Response = error(423, "locked", "hub is locked; ask Remu support")
        else:
            response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Cache-Control"] = "no-store"
        response.headers["Referrer-Policy"] = "no-referrer"
        return response

    @app.get("/health")
    def health() -> dict[str, Any]:
        return hub.health()

    @app.post("/pair")
    def pair(body: PairIn, request: Request) -> Any:
        client = request.client.host if request.client else "unknown"
        if pair_limiter.blocked(client) or shop_limiter.blocked("pair"):
            return error(429, "rate_limited", "too many wrong codes, wait a minute")
        granted = auth.redeem_code(hub.db, body.code)
        if granted is None:
            pair_limiter.hit(client)
            shop_limiter.hit("pair")  # a 6-digit invite must not be guessable from many addresses
            return error(401, "unauthorized", "wrong or used code")
        if granted == "owner":
            auth.revoke_role(hub.db, "owner")  # re-pairing replaces the old owner phone
        token = auth.issue_token(hub.db, granted)
        return {
            "app_token": token,
            "cert_sha256": cert_fp,
            "shop_id": hub.settings.shop_id,
            "role": granted,
        }

    @app.post("/pair/invite")
    def invite(_: Owner, __: Staff) -> dict[str, str]:
        code, expires = auth.create_invite(hub.db)
        return {"invite_code": code, "expires_at": expires}

    @app.post("/staff", status_code=201)
    def staff_add(
        body: StaffIn,
        _: Owner,
        x_remu_staff_session: Annotated[str | None, Header()] = None,
    ) -> Any:
        first_owner = hub.db.one("SELECT 1 FROM staff") is None
        if not first_owner and sessions.staff_for(x_remu_staff_session) is None:
            return error(401, "staff_session_required", "enter your PIN again")
        try:
            sid = auth.add_staff(hub.db, hub.settings.shop_id, body.name, body.role, body.pin)
        except ValueError as exc:
            return error(400, "invalid", str(exc))
        return {"staff_id": sid}

    @app.get("/tokens")
    def tokens(_: Owner) -> list[dict[str, Any]]:
        return auth.list_tokens(hub.db)

    @app.post("/tokens/{token_id}/revoke")
    def token_revoke(token_id: str, _: Owner, __: Staff) -> Any:
        if not auth.revoke_token(hub.db, token_id):
            return error(404, "not_found", "no such active token")
        return {"status": "revoked"}

    @app.get("/staff")
    def staff_list(_: Role) -> list[dict[str, Any]]:
        return auth.list_staff(hub.db)

    @app.post("/staff/verify-pin")
    def verify_pin(body: PinIn, _: Role, request: Request) -> Any:
        key = "pin:" + (request.client.host if request.client else "unknown")
        if pair_limiter.blocked(key) or shop_limiter.blocked("pin"):
            return error(429, "rate_limited", "too many wrong PINs, wait a minute")
        found = auth.verify_pin(hub.db, body.pin)
        if found is None:
            pair_limiter.hit(key)
            shop_limiter.hit("pin")
            return error(401, "unauthorized", "wrong PIN")
        return found | {"staff_session": sessions.issue(found["staff_id"])}

    @app.post("/enrol", status_code=201)
    def enrol(body: EnrolModel, sid: Staff) -> Any:
        if body.staff_id and body.staff_id != sid:
            return error(400, "invalid", "staff_id does not match the PIN session")
        req = EnrolIn(
            **(body.model_dump(exclude={"faces"}) | {"staff_id": sid}),
            faces=[FaceIn(**f.model_dump()) for f in body.faces],
        )
        try:
            return hub.enrol(req)
        except EnrolError as exc:
            return error(exc.status, exc.code, exc.detail, **exc.extra)

    @app.get("/members")
    def members(
        _: Role, q: str | None = None, phone: str | None = None, status: str | None = None
    ) -> list[dict[str, Any]]:
        return hub.search(q, phone, status)

    @app.get("/members/{member_id}")
    def member(member_id: str, _: Role) -> dict[str, Any]:
        return hub.member_card(member_id)

    @app.patch("/members/{member_id}")
    async def member_patch(member_id: str, request: Request, _: Staff) -> Any:
        body = await request.json()
        if not isinstance(body, dict):
            return error(400, "invalid", "body must be an object")
        try:
            return hub.patch_member(member_id, body)
        except ValueError as exc:
            return error(400, "invalid", str(exc))

    @app.post("/members/{member_id}/remove")
    def member_remove(member_id: str, body: RemoveIn, sid: Staff) -> Any:
        if body.staff_id and body.staff_id != sid:
            return error(400, "invalid", "staff_id does not match the PIN session")
        try:
            return hub.withdraw(member_id, "owner_remove", actor=sid)
        except mdb.InvalidTransition as exc:
            return error(409, "conflict", str(exc))

    @app.post("/members/{member_id}/purchases", status_code=201)
    def member_purchase(member_id: str, body: PurchaseIn, _: Staff) -> Any:
        hub.member_summary(member_id)
        return mdb.add_purchase(
            hub.db,
            member_id,
            bill_no=body.bill_no,
            amount=body.amount,
            items=body.items,
            occasion=body.occasion,
        )

    @app.post("/households", status_code=201)
    def household_create(body: HouseholdIn, _: Staff) -> Any:
        try:
            hid = mdb.create_household(
                hub.db, hub.settings.shop_id, body.label, body.member_ids, body.payer_member_id
            )
        except mdb.InvalidTransition as exc:
            return error(409, "conflict", str(exc))
        except ValueError as exc:
            return error(400, "invalid", str(exc))
        return {"id": hid}

    @app.patch("/households/{household_id}")
    def household_patch(household_id: str, body: HouseholdPatch, _: Staff) -> Any:
        db = hub.db
        try:
            with db.tx():
                current = mdb.household_payload(db, household_id)
                for mid in body.add_member_ids:
                    r = db.one("SELECT household_id FROM members WHERE id = ?", (mid,))
                    if r is None:
                        raise LookupError(mid)
                    if r["household_id"] and r["household_id"] != household_id:
                        raise mdb.InvalidTransition(f"{mid} already belongs to a household")
                    db.execute(
                        "UPDATE members SET household_id = ? WHERE id = ?", (household_id, mid)
                    )
                    mdb.queue_member(db, mid)
                for mid in body.remove_member_ids:
                    db.execute(
                        "UPDATE members SET household_id = NULL WHERE id = ? AND "
                        "household_id = ?",
                        (mid, household_id),
                    )
                    mdb.queue_member(db, mid)
                if body.label is not None:
                    db.execute(
                        "UPDATE households SET label = ? WHERE id = ?", (body.label, household_id)
                    )
                payer = body.payer_member_id or current["payer_member_id"]
                remaining = mdb.household_payload(db, household_id)["member_ids"]
                if payer not in remaining:
                    if body.payer_member_id:
                        raise ValueError("payer must be a member of the household")
                    payer = remaining[0] if remaining else None
                db.execute(
                    "UPDATE households SET payer_member_id = ? WHERE id = ?", (payer, household_id)
                )
                mdb.queue_household(db, household_id)
                return mdb.household_payload(db, household_id)
        except mdb.InvalidTransition as exc:
            return error(409, "conflict", str(exc))
        except ValueError as exc:
            return error(400, "invalid", str(exc))

    @app.get("/visits")
    def visits(_: Role, member_id: Annotated[str, Query()]) -> list[dict[str, Any]]:
        return vdb.list_visits(hub.db, member_id)

    @app.patch("/visits/{visit_id}")
    def visit_patch(visit_id: str, body: VisitPatch, sid: Staff) -> Any:
        if body.greeted_by is not None and body.greeted_by != sid:
            return error(400, "invalid", "greeted_by must be the staff member of this session")
        fb = vdb.set_feedback(
            hub.db,
            visit_id,
            greeted=body.greeted,
            greeted_by=body.greeted_by,
            not_them=body.not_them,
        )
        hub.bus.publish("visit_feedback", fb)
        return fb

    @app.get("/clips/{clip_id}")
    def clip(clip_id: str, request: Request, _: Owner) -> Response:
        if not is_lan(request.client.host if request.client else None):
            return error(403, "forbidden", "clips play only on the shop network")
        return Response(hub.clips.read(clip_id), media_type="video/mp4")

    @app.get("/consent/export")
    def consent_export(_: Owner) -> PlainTextResponse:
        from remu_hub.export import consent_csv

        return PlainTextResponse(consent_csv(hub.db), media_type="text/csv")

    def run_discovery(user: str, password: str, subnet: str | None) -> dict[str, Any]:
        import base64

        from remu_hub.video import discover as disc

        found = disc.discover(subnet or disc.local_subnet(), user, password)
        streams = []
        for c in found:
            kind = "sub" if c.substream else "main"
            label = f"{c.brand} {c.host} channel {c.channel} ({c.width}x{c.height}, {kind})"
            streams.append(
                {
                    "id": f"{c.host}/{c.brand}/{c.channel}/{kind}",
                    "label": label,
                    "url": c.url,
                    "host": c.host,
                    "brand": c.brand,
                    "channel": c.channel,
                    "substream": c.substream,
                    "width": c.width,
                    "height": c.height,
                    "thumbnail_jpeg_b64": base64.b64encode(c.thumbnail_jpeg).decode(),
                }
            )
        return {"streams": streams}

    @app.get("/discover")
    def discover_saved(_: Owner) -> dict[str, Any]:
        """Uses the DVR login saved by POST /discover, or REMU_DVR_USER and REMU_DVR_PASSWORD."""
        import os

        user = hub.db.get_kv("dvr_user") or os.environ.get("REMU_DVR_USER") or "admin"
        sealed = hub.db.get_kv("dvr_password")
        password = (
            (hub.open_secret(sealed) if sealed else None)
            or os.environ.get("REMU_DVR_PASSWORD")
            or ""
        )
        return run_discovery(user, password, None)

    @app.post("/discover")
    def discover_streams(body: DiscoverIn, _: Owner, __: Staff) -> dict[str, Any]:
        """Runs discovery with the DVR login in the body and keeps it for GET /discover."""
        hub.db.set_kv("dvr_user", body.user)
        hub.db.set_kv("dvr_password", hub.seal_secret(body.password))
        return run_discovery(body.user, body.password, body.subnet)

    @app.put("/settings/streams")
    def save_streams(body: StreamsIn, _: Owner, __: Staff) -> dict[str, str]:
        if not body.gate_url.startswith("rtsp://"):
            raise HTTPException(400, "gate_url must be an rtsp:// URL")
        hub.db.set_kv("rtsp_gate_url", body.gate_url)
        return {"status": "saved", "note": "restart the hub service to use the new stream"}

    @app.get("/preview")
    def preview(request: Request, _: Owner) -> Response:
        from fastapi.responses import StreamingResponse

        from remu_hub.video.preview import BOUNDARY, mjpeg
        from remu_hub.video.source import RtspSource

        if not is_lan(request.client.host if request.client else None):
            return error(403, "forbidden", "preview works only on the shop network")
        url = hub.db.get_kv("rtsp_gate_url") or hub.settings.rtsp_gate_url
        source = RtspSource(url, fps=5.0)
        stream = mjpeg(
            (tf.frame for tf in source.frames()),
            lambda f: hub.engine.detect(f),
            min_face_px=hub.settings.min_face_px,
        )

        def body() -> Any:
            try:
                yield from stream
            finally:
                source.close()

        return StreamingResponse(
            body(), media_type=f"multipart/x-mixed-replace; boundary=" f"{BOUNDARY}"
        )

    @app.websocket("/ws")
    async def ws(socket: WebSocket) -> None:
        await socket.accept()
        try:
            first = json.loads(await asyncio.wait_for(socket.receive_text(), timeout=10))
        except (TimeoutError, ValueError, WebSocketDisconnect):
            await socket.close(code=4401)
            return
        token = first.get("token") if isinstance(first, dict) else None
        # Alerts carry names, notes and purchases: Owner mode only (docs/SECURITY.md section 2).
        if not isinstance(token, str) or auth.token_role(hub.db, token) != "owner":
            await socket.close(code=4401)
            return
        if hub.locked:
            await socket.close(code=4423)
            return
        queue = hub.bus.subscribe()
        try:
            await socket.send_json(
                {
                    "type": "hello",
                    "id": None,
                    "ts": None,
                    "payload": {"shop_id": hub.settings.shop_id},
                }
            )
            for event in hub.bus.since(first.get("since")):
                await socket.send_json(event)
            while True:
                try:
                    event = await asyncio.wait_for(queue.get(), timeout=PING_S)
                except TimeoutError:
                    event = {"type": "ping"}
                await socket.send_json(event)
        except (WebSocketDisconnect, RuntimeError):
            pass
        finally:
            hub.bus.unsubscribe(queue)
            with contextlib.suppress(RuntimeError):
                await socket.close()

    return app
