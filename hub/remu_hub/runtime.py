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
        warned = 0.0
        while True:
            hub.stream_state = source.state
            hub.fps = pipe.fps
            if 0 < pipe.fps < 5.0 and time.monotonic() - warned > 300:
                # TASK-104: report it, never switch the model pack automatically.
                log.warning("hub sustains only %.1f fps (target 5 to 8)", pipe.fps)
                warned = time.monotonic()
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


class CounterClips:
    """Optional counter stream (TASK-108): feeds only a second clip and the aim preview.

    No face in it is detected, embedded or matched: the notice promises
    recognition at the door only.
    """

    def __init__(self, hub: Hub, url: str) -> None:
        from remu_hub.clips import ClipRecorder

        self.recorder = ClipRecorder(hub.clips)
        self.source = RtspSource(url, fps=6.0)

    def start(self) -> None:
        def loop() -> None:
            for tf in self.source.frames():
                self.recorder(tf)

        threading.Thread(target=loop, name="counter-stream", daemon=True).start()


def serve(settings: Settings, *, with_pipeline: bool = True, with_sync: bool = True) -> int:
    import os

    from remu_hub.observability import init_sentry
    from remu_hub.recovery import recover_if_corrupt

    init_sentry(settings.sentry_dsn, settings.shop_id)
    if settings.require_encrypted and not os.path.ismount(settings.data_dir):
        log.error("%s is not a mounted encrypted volume; refusing to start", settings.data_dir)
        return 1  # systemd restarts the unit, and ExecStartPre unlocks again
    recover_if_corrupt(settings.db_path)
    hub = Hub(settings)
    cert, key = ensure_cert(settings.certs_dir)
    app = create_app(hub, cert)
    start_gallery_refresh(hub)
    pipeline_thread: threading.Thread | None = None
    if with_pipeline:
        hub.engine  # load models before serving  # noqa: B018
        _, pipeline_thread = start_pipeline(hub, settings)
        if settings.rtsp_counter_url:
            counter = CounterClips(hub, settings.rtsp_counter_url)
            counter.start()
            original = hub.recorder.start

            def both(visit_id: str, first_ts: float) -> None:
                original(visit_id, first_ts)
                counter.recorder.start(visit_id, first_ts)

            hub.recorder.start = both  # type: ignore[method-assign]
    if with_sync and settings.cloud_url:
        from remu_hub.sync.loop import start_sync

        start_sync(hub, settings)
    from remu_hub.systemd import start_watchdog

    def alive() -> bool:
        return pipeline_thread is None or pipeline_thread.is_alive()

    # READY=1 once the server is listening; the watchdog stops pinging if the gate loop dies.
    app.router.on_startup.append(lambda: start_watchdog(alive))
    uvicorn.run(
        app,
        host=settings.api_host,
        port=settings.api_port,
        ssl_certfile=str(cert),
        ssl_keyfile=str(key),
        log_level=settings.log_level.lower(),
        access_log=False,  # query strings carry phone numbers and names
    )
    return 0
