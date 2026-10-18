"""Outbound-only HTTPS client for the cloud (docs/API_CONTRACT.md section 2).

The hub calls the cloud; the cloud never calls the hub, and the hub opens no
port beyond the LAN API.
"""

from __future__ import annotations

from typing import Any

import httpx

from remu_hub import __version__


class CloudError(Exception):
    """A network error or a 5xx: retry the whole batch later."""


class CloudRejected(Exception):
    """A 4xx for the whole request (bad token, revoked device)."""

    def __init__(self, status: int, body: str) -> None:
        super().__init__(f"{status}: {body[:200]}")
        self.status = status


class CloudClient:
    def __init__(
        self,
        base_url: str,
        device_token: str | None = None,
        *,
        http: httpx.Client | None = None,
        timeout: float = 15.0,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.device_token = device_token
        self.http = http or httpx.Client(
            timeout=timeout, headers={"User-Agent": f"remu-hub/{__version__}"}
        )

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.device_token}"} if self.device_token else {}

    def _call(self, method: str, path: str, **kw: Any) -> httpx.Response:
        try:
            r = self.http.request(method, self.base_url + path, headers=self._headers(), **kw)
        except httpx.HTTPError as exc:
            raise CloudError(str(exc)) from exc
        if r.status_code >= 500:
            raise CloudError(f"{r.status_code}")
        if r.status_code >= 400:
            raise CloudRejected(r.status_code, r.text)
        return r

    def post_events(self, events: list[dict[str, Any]]) -> list[dict[str, Any]]:
        r = self._call("POST", "/api/hub/events", json={"events": events})
        results: list[dict[str, Any]] = r.json().get("results", [])
        return results

    def inbox(self, after: str | None) -> dict[str, Any]:
        params = {"after": after} if after else {}
        body: dict[str, Any] = self._call("GET", "/api/hub/inbox", params=params).json()
        return body

    def anchor(self, date: str, chain_head_hash: str) -> dict[str, Any]:
        body: dict[str, Any] = self._call(
            "POST", "/api/hub/anchor", json={"date": date, "chain_head_hash": chain_head_hash}
        ).json()
        return body

    def claim(
        self, pairing_code: str, serial: str, cert_sha256: str | None = None
    ) -> dict[str, Any]:
        body_out: dict[str, Any] = {"pairing_code": pairing_code, "serial": serial}
        if cert_sha256:
            body_out["cert_sha256"] = cert_sha256  # lets Owner mode check the pin (review M3)
        r = self._call("POST", "/api/devices/claim", json=body_out)
        body: dict[str, Any] = r.json()
        return body

    def unlock(self) -> str:
        return str(self._call("POST", "/api/devices/unlock").json()["unlock_token"])
