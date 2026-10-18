"""The sync loop: claim, outbox flush, inbox poll, with backoff (TASK-057, 058, 067)."""

from __future__ import annotations

import logging
import os
import platform
import threading
from pathlib import Path
from typing import Any

from remu_hub.hub import Hub
from remu_hub.settings import Settings
from remu_hub.sync import inbox, outbox
from remu_hub.sync.client import CloudClient, CloudError, CloudRejected

log = logging.getLogger(__name__)
INTERVAL_S = 30.0
BACKOFF_START_S = 5.0
BACKOFF_MAX_S = 300.0
BATCH = 200


def device_token_path(settings: Settings) -> Path:
    return settings.state_dir / "device.token"


def load_device_token(settings: Settings) -> str | None:
    if settings.device_token:
        return settings.device_token
    p = device_token_path(settings)
    return p.read_text().strip() if p.exists() else None


def save_device_token(settings: Settings, token: str) -> None:
    p = device_token_path(settings)
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as fh:
        fh.write(token)


def hub_serial() -> str:
    try:
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.lower().startswith("serial"):
                return line.split(":", 1)[1].strip()
    except OSError:
        pass
    return platform.node() or "dev-hub"


def cert_fingerprint(settings: Settings) -> str | None:
    from remu_hub.api.tls import fingerprint

    cert = settings.certs_dir / "hub.crt"
    return fingerprint(cert) if cert.exists() else None


def claim(
    settings: Settings, client: CloudClient, pairing_code: str, hub: Hub | None = None
) -> bool:
    """One claim poll. On success the device token is saved and the claiming shop recorded.

    The certificate fingerprint goes with it so Owner mode can compare the pin it took on the
    LAN with what the hub told the cloud (review M3), and `cloud_shop_id` is shown on
    GET /health so the owner can see which shop claimed the hub (review M7).
    """
    body = client.claim(pairing_code, hub_serial(), cert_fingerprint(settings))
    if body.get("status") != "paired" or not body.get("device_token"):
        return False
    token = str(body["device_token"])
    save_device_token(settings, token)
    client.device_token = token
    shop = str(body.get("shop_id") or "")
    (settings.state_dir / "cloud_shop_id").write_text(shop)
    if shop and shop != settings.shop_id:
        log.error("claimed by cloud shop %s but REMU_SHOP_ID is %s", shop, settings.shop_id)
    if hub is not None:
        hub.cloud_shop_id = shop or None
    log.info("paired with the cloud as device %s", body.get("device_id"))
    return True


class SyncWorker:
    def __init__(self, hub: Hub, client: CloudClient) -> None:
        self.hub = hub
        self.client = client
        self.wake = threading.Event()
        self.backoff = BACKOFF_START_S
        self._stop = threading.Event()
        outbox.on_enqueue(self._on_enqueue)

    def _on_enqueue(self, event_type: str) -> None:
        if event_type in ("alert", "notice_request"):
            self.wake.set()

    def claim_once(self, pairing_code: str) -> bool:
        return claim(self.hub.settings, self.client, pairing_code, self.hub)

    def flush_once(self) -> int:
        sent = 0
        while True:
            batch = outbox.pending(self.hub.db, BATCH)
            if not batch:
                return sent
            try:
                results = self.client.post_events(batch)
            except CloudRejected as exc:
                if exc.status != 400 or len(batch) == 1:
                    raise
                results = self._one_by_one(batch)  # find the bad event, keep the rest flowing
            by_id = {r.get("id"): r for r in results}
            acked = [e["id"] for e in batch if by_id.get(e["id"], {}).get("status") == "acked"]
            outbox.mark_synced(self.hub.db, acked)
            for e in batch:
                r = by_id.get(e["id"])
                if r and r.get("status") == "rejected":
                    outbox.mark_failed(self.hub.db, e["id"], str(r.get("reason", "rejected")))
            sent += len(batch)
            if len(acked) + sum(1 for r in results if r.get("status") == "rejected") == 0:
                raise CloudError("cloud acknowledged nothing")
            if len(batch) < BATCH:
                return sent

    def _one_by_one(self, batch: list[dict[str, Any]]) -> list[dict[str, Any]]:
        out: list[dict[str, Any]] = []
        for event in batch:
            try:
                out.extend(self.client.post_events([event]))
            except CloudRejected as exc:
                if exc.status != 400:
                    raise
                out.append({"id": event["id"], "status": "rejected", "reason": f"400: {exc}"})
        return out

    def cycle(self) -> None:
        if not self.client.device_token:
            code_file = self.hub.settings.state_dir / "pairing.txt"
            if not code_file.exists() or not self.claim_once(code_file.read_text().strip()):
                return
        # The inbox carries STOP withdrawals: poll it even when the flush fails (H1).
        try:
            self.flush_once()
        finally:
            inbox.poll(self.hub, self.client)

    def run(self) -> None:
        while not self._stop.is_set():
            try:
                self.cycle()
                self.hub.internet = True
                self.backoff = BACKOFF_START_S
                wait = INTERVAL_S
            except CloudRejected as exc:
                log.error("cloud rejected the hub: %s", exc)
                self.hub.internet = True
                wait = INTERVAL_S
            except CloudError as exc:
                self.hub.internet = False
                log.warning("cloud unreachable (%s), retrying in %.0fs", exc, self.backoff)
                wait = self.backoff
                self.backoff = min(self.backoff * 2, BACKOFF_MAX_S)
            except Exception:
                log.exception("sync cycle failed")
                wait = self.backoff
            self.wake.wait(wait)
            self.wake.clear()

    def stop(self) -> None:
        self._stop.set()
        self.wake.set()


def start_sync(hub: Hub, settings: Settings, client: Any = None) -> SyncWorker:
    assert settings.cloud_url
    worker = SyncWorker(hub, client or CloudClient(settings.cloud_url, load_device_token(settings)))
    threading.Thread(target=worker.run, name="cloud-sync", daemon=True).start()
    return worker
