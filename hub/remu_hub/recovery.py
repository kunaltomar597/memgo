"""SQLite crash recovery (TASK-120).

WAL mode with synchronous=FULL keeps the chain intact through power loss.
A daily copy of the database lives inside the (encrypted) data directory and
EXCLUDES the `templates` and `clips` tables, so restoring a copy can never
bring back a deleted template. After a restore the members whose templates
are gone must be re-enrolled, which is the safe failure.
"""

from __future__ import annotations

import logging
import shutil
import sqlite3
from pathlib import Path

log = logging.getLogger(__name__)
EXCLUDED = ("templates", "clips")


def backup_path(db_path: Path) -> Path:
    return db_path.with_name("remu.daily.sqlite")


def integrity_ok(db_path: Path) -> bool:
    if not db_path.exists():
        return True
    try:
        conn = sqlite3.connect(str(db_path))
        try:
            row = conn.execute("PRAGMA integrity_check").fetchone()
            return bool(row and row[0] == "ok")
        finally:
            conn.close()
    except sqlite3.DatabaseError:
        return False


def daily_copy(db_path: Path) -> Path:
    target = backup_path(db_path)
    tmp = target.with_suffix(".tmp")
    tmp.unlink(missing_ok=True)
    src = sqlite3.connect(str(db_path))
    try:
        dst = sqlite3.connect(str(tmp))
        try:
            src.backup(dst)
            dst.execute("PRAGMA secure_delete = ON")
            for table in EXCLUDED:
                dst.execute(f"DELETE FROM {table}")  # noqa: S608 - fixed table names
            dst.execute("UPDATE visits SET clip_id = NULL")
            dst.commit()
            dst.execute("VACUUM")
        finally:
            dst.close()
    finally:
        src.close()
    tmp.replace(target)
    return target


def recover_if_corrupt(db_path: Path) -> bool:
    """At start: if the database is corrupt, keep it aside and restore the daily copy."""
    if integrity_ok(db_path):
        return False
    stamp = db_path.with_name(db_path.name + ".corrupt")
    log.error("database corrupt; moving it to %s and restoring the daily copy", stamp)
    shutil.move(str(db_path), str(stamp))
    for suffix in ("-wal", "-shm"):
        Path(str(db_path) + suffix).unlink(missing_ok=True)
    copy = backup_path(db_path)
    if copy.exists():
        shutil.copy2(copy, db_path)
    return True
