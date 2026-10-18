"""SQLite access: connection settings and numbered, idempotent migrations."""

from __future__ import annotations

import datetime as dt
import sqlite3
from importlib import resources
from pathlib import Path

IST = dt.timezone(dt.timedelta(hours=5, minutes=30))


def now_iso(clock: dt.datetime | None = None) -> str:
    """Wall-clock time in IST with an offset, seconds precision."""
    t = clock or dt.datetime.now(IST)
    return t.astimezone(IST).isoformat(timespec="seconds")


def connect(path: Path | str) -> sqlite3.Connection:
    if str(path) != ":memory:":
        Path(path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA journal_mode = WAL")
    conn.execute("PRAGMA synchronous = FULL")
    conn.execute("PRAGMA secure_delete = ON")  # deleted template bytes are zeroed on disk
    return conn


def migration_files() -> list[tuple[int, str]]:
    folder = resources.files("remu_hub.db") / "migrations"
    out = []
    for entry in folder.iterdir():
        name = entry.name
        if name.endswith(".sql") and name[:4].isdigit():
            out.append((int(name[:4]), entry.read_text()))
    return sorted(out)


def migrate(conn: sqlite3.Connection) -> list[int]:
    """Apply pending migrations in order. Safe to run any number of times."""
    conn.execute(
        "CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY,"
        " applied_at TEXT NOT NULL)"
    )
    done = {r[0] for r in conn.execute("SELECT version FROM schema_migrations")}
    applied = []
    for version, sql in migration_files():
        if version in done:
            continue
        conn.execute("BEGIN")
        try:
            for stmt in _statements(sql):
                conn.execute(stmt)
            conn.execute("INSERT INTO schema_migrations VALUES (?, ?)", (version, now_iso()))
            conn.execute("COMMIT")
        except Exception:
            conn.execute("ROLLBACK")
            raise
        applied.append(version)
    return applied


def _statements(sql: str) -> list[str]:
    """Split a migration into statements, keeping trigger bodies whole."""
    lines = [ln for ln in sql.splitlines() if not ln.strip().startswith("--")]
    out, buf = [], []
    for line in lines:
        buf.append(line)
        joined = "\n".join(buf).strip()
        if joined.endswith(";") and sqlite3.complete_statement(joined):
            out.append(joined)
            buf = []
    if "\n".join(buf).strip():
        out.append("\n".join(buf).strip())
    return out
