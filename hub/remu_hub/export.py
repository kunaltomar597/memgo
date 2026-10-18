"""Local consent export as CSV, same columns as the cloud export (TASK-095)."""

from __future__ import annotations

import csv
import io

from remu_hub.db.consent import COLUMNS
from remu_hub.db.database import Database


def consent_csv(db: Database, shop_id: str | None = None) -> str:
    buf = io.StringIO()
    w = csv.writer(buf, lineterminator="\n")
    w.writerow(COLUMNS)
    where = "WHERE shop_id = ? " if shop_id else ""  # fixed fragment, value bound below
    sql = f"SELECT * FROM consent_records {where}ORDER BY rowid"  # noqa: S608
    for r in db.all(sql, (shop_id,) if shop_id else ()):
        w.writerow(["" if r[c] is None else r[c] for c in COLUMNS])
    return buf.getvalue()
