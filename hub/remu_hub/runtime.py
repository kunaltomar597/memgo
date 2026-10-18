"""Run the hub: LAN API, gate pipeline and background loops in one process."""

from __future__ import annotations

import logging
import threading
import time

import uvicorn

from remu_hub.api.app import create_app
from remu_hub.api.tls import ensure_cert
from remu_hub.hub import Hub
from remu_hub.match.decide import Decision
from remu_hub.pipeline import Pipeline
from remu_hub.settings import Settings
from remu_hub.video.source import RtspSource, TimedFrame

log = logging.getLogger(__name__)
GALLERY_REFRESH_S = 5.0


class HubSink:
    def __init__(self, hub: Hub) -> None:
        self.hub = hub

    def on_decision(self, decision: Decision, frame: TimedFrame) -> None:
        self.hub.on_decision(decision, frame)
        if decision.kind.value == "alert":
            log.info(
                "ALERT member=%s score=%.3f frames=%d/%d",
                decision.member_id,
                decision.score,
                decision.frames_agreed,
                decision.window,
            )


def start_pipeline(hub: Hub, settings: Settings) -> tuple[Pipeline, threading.Thread]:
    source = RtspSource(settings.rtsp_gate_url, fps=6.0)
    pipe = Pipeline(source, hub.engine, hub.gallery, hub.decider, [HubSink(hub)], [hub.recorder])

    def loop() -> None:
        while True:
            try:
                pipe.run()
                return
            except Exception:
                log.exception("pipeline crashed, restarting in 2s")
                time.sleep(2)

    def watch() -> None:
        while True:
            hub.stream_state = source.state
            hub.fps = pipe.fps
            time.sleep(1)

    t = threading.Thread(target=loop, name="gate-pipeline", daemon=True)
    t.start()
    threading.Thread(target=watch, name="pipeline-watch", daemon=True).start()
    return pipe, t


def start_gallery_refresh(hub: Hub) -> threading.Thread:
    def loop() -> None:
        while True:
            time.sleep(GALLERY_REFRESH_S)
            try:
                hub.refresh_gallery()
            except Exception:
                log.exception("gallery refresh failed")

    t = threading.Thread(target=loop, name="gallery-refresh", daemon=True)
    t.start()
    return t


def serve(settings: Settings, *, with_pipeline: bool = True, with_sync: bool = True) -> int:
    hub = Hub(settings)
    cert, key = ensure_cert(settings.certs_dir)
    app = create_app(hub, cert)
    start_gallery_refresh(hub)
    if with_pipeline:
        hub.engine  # load models before serving  # noqa: B018
        start_pipeline(hub, settings)
    if with_sync and settings.cloud_url:
        from remu_hub.sync.loop import start_sync

        start_sync(hub, settings)
    uvicorn.run(
        app,
        host=settings.api_host,
        port=settings.api_port,
        ssl_certfile=str(cert),
        ssl_keyfile=str(key),
        log_level=settings.log_level.lower(),
    )
    return 0
