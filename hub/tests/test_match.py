from __future__ import annotations

import time

import numpy as np
import pytest

from remu_hub.match.decide import Decider, Kind
from remu_hub.match.gallery import Gallery, Match


def _unit(seed: int) -> np.ndarray:
    v = np.random.default_rng(seed).standard_normal(512).astype(np.float32)
    return v / np.linalg.norm(v)


def test_empty_gallery_matches_nothing() -> None:
    assert Gallery().match(_unit(0)) is None


def test_exact_match_and_threshold() -> None:
    g = Gallery(0.45)
    g.add("m_a", _unit(1))
    g.add("m_b", _unit(2))
    m = g.match(_unit(1))
    assert m is not None and m.member_id == "m_a" and m.score == pytest.approx(1.0, abs=1e-5)
    assert g.match(_unit(3)) is None  # random vectors score near 0


def test_below_threshold_is_none() -> None:
    g = Gallery(0.45)
    a = _unit(1)
    noise = _unit(9)
    probe = 0.4 * a + np.sqrt(1 - 0.16) * (noise - (noise @ a) * a) / np.linalg.norm(
        noise - (noise @ a) * a
    )
    g.add("m_a", a)
    best = g.best(probe)
    assert best is not None and best.score == pytest.approx(0.4, abs=1e-3)
    assert g.match(probe) is None


def test_add_remove_replace() -> None:
    g = Gallery()
    g.add("m_a", _unit(1))
    g.replace("m_a", _unit(2))
    assert g.match(_unit(2)) is not None and g.match(_unit(1)) is None
    assert g.remove("m_a") and "m_a" not in g and len(g) == 0
    assert not g.remove("m_a")


def test_5000_templates_under_5ms_per_probe() -> None:
    g = Gallery()
    rng = np.random.default_rng(0)
    vecs = rng.standard_normal((5000, 512)).astype(np.float32)
    for i, v in enumerate(vecs):
        g.add(f"m_{i}", v)
    probe = _unit(77)
    g.match(probe)  # build the matrix once
    n = 200
    t = time.perf_counter()
    for _ in range(n):
        g.match(probe)
    per_probe_ms = (time.perf_counter() - t) / n * 1000
    assert per_probe_ms < 5.0


def _seq(decider: Decider, results: list[str | None], start: float = 0.0, track: int = 1):
    out = []
    for i, r in enumerate(results):
        m = Match(r, 0.8) if r else None
        d = decider.observe(track, m, start + i * 0.2)
        if d:
            out.append(d)
    return out


def test_three_of_five_alerts_once() -> None:
    d = Decider(3, 5, 600)
    got = _seq(d, ["m_a", None, "m_a", None, "m_a", "m_a", "m_a"])
    assert len(got) == 1
    assert (
        got[0].kind is Kind.ALERT
        and got[0].frames_agreed == 3
        and got[0].score == pytest.approx(0.8)
    )
    assert got[0].first_ts == 0.0


def test_two_of_five_does_not_alert() -> None:
    assert _seq(Decider(3, 5, 600), ["m_a", None, None, "m_a", None, None, None]) == []


def test_split_votes_do_not_alert() -> None:
    assert _seq(Decider(3, 5, 600), ["m_a", "m_b", "m_a", "m_b", None]) == []


def test_cooldown_blocks_then_expires() -> None:
    d = Decider(3, 5, cooldown_s=600)
    first = _seq(d, ["m_a"] * 3, start=0, track=1)
    again = _seq(d, ["m_a"] * 3, start=300, track=2)
    later = _seq(d, ["m_a"] * 3, start=700, track=3)
    assert [x.kind for x in first + again + later] == [Kind.ALERT, Kind.SEEN, Kind.ALERT]


def test_tracks_expire() -> None:
    d = Decider(3, 5, 600, track_ttl_s=2.0)
    _seq(d, ["m_a"], start=0, track=1)
    assert d.active_tracks == 1
    d.observe(2, None, 10.0)
    assert d.active_tracks == 1
