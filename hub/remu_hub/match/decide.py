"""Frame agreement (3 of the last 5 frames of one face track) and the alert cooldown.

A track that reaches agreement produces exactly one decision. A member
alerted in the last `cooldown_s` seconds produces a `seen` decision instead
of an `alert` (the visit updates `last_seen_at` only).
"""

from __future__ import annotations

from collections import Counter, deque
from dataclasses import dataclass, field
from enum import Enum

from remu_hub.match.gallery import Match


class Kind(str, Enum):
    ALERT = "alert"
    SEEN = "seen"


@dataclass(frozen=True)
class Decision:
    kind: Kind
    member_id: str
    score: float  # mean of the agreeing frames
    frames_agreed: int
    window: int
    first_ts: float  # timestamp of the first agreeing frame
    ts: float  # timestamp of the deciding frame
    track_id: int


@dataclass
class _Track:
    window: deque[tuple[str | None, float, float]] = field(default_factory=deque)
    decided: bool = False
    last_ts: float = 0.0


class Decider:
    def __init__(
        self,
        required: int = 3,
        window: int = 5,
        cooldown_s: float = 600.0,
        track_ttl_s: float = 2.0,
    ) -> None:
        if required > window:
            raise ValueError("required cannot exceed window")
        self.required = required
        self.window = window
        self.cooldown_s = cooldown_s
        self.track_ttl_s = track_ttl_s
        self._tracks: dict[int, _Track] = {}
        self._last_alert: dict[str, float] = {}

    def observe(self, track_id: int, match: Match | None, ts: float) -> Decision | None:
        self._expire(ts)
        tr = self._tracks.setdefault(track_id, _Track(deque(maxlen=self.window)))
        tr.last_ts = ts
        tr.window.append((match.member_id if match else None, match.score if match else 0.0, ts))
        if tr.decided:
            return None
        votes = Counter(m for m, _, _ in tr.window if m is not None)
        if not votes:
            return None
        member_id, count = votes.most_common(1)[0]
        if count < self.required:
            return None
        tr.decided = True
        agreeing = [(s, t) for m, s, t in tr.window if m == member_id]
        score = sum(s for s, _ in agreeing) / len(agreeing)
        last = self._last_alert.get(member_id)
        if last is not None and ts - last < self.cooldown_s:
            kind = Kind.SEEN
        else:
            kind = Kind.ALERT
            self._last_alert[member_id] = ts
        return Decision(kind, member_id, score, count, self.window, agreeing[0][1], ts, track_id)

    def _expire(self, now: float) -> None:
        for tid in [t for t, tr in self._tracks.items() if now - tr.last_ts > self.track_ttl_s]:
            del self._tracks[tid]

    def forget_member(self, member_id: str) -> None:
        """Drop cooldown state for a withdrawn member."""
        self._last_alert.pop(member_id, None)

    @property
    def active_tracks(self) -> int:
        return len(self._tracks)
