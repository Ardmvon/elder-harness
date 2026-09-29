"""SQLite storage for the trusted-circle server.

Deliberately plain: one file, stdlib ``sqlite3``, no ORM. The data here is small (one row per
device, a handful per circle, a few events a day) and being able to read the whole schema on one
screen matters more than convenience.
"""

from __future__ import annotations

import json
import os
import secrets
import sqlite3
import time
from contextlib import contextmanager
from typing import Any, Iterator

DB_PATH = os.environ.get("HOTLINE_DB", os.path.join(os.path.dirname(__file__), "..", "hotline.db"))

SCHEMA = """
CREATE TABLE IF NOT EXISTS devices (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    token         TEXT NOT NULL UNIQUE,
    pair_code     TEXT NOT NULL UNIQUE,
    elder_name    TEXT NOT NULL DEFAULT '',
    created_at    REAL NOT NULL,
    last_seen_at  REAL,
    last_seen_note TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS circle (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id  INTEGER NOT NULL REFERENCES devices(id),
    name       TEXT NOT NULL,
    phone      TEXT NOT NULL DEFAULT '',
    -- family sees everything about the person; community and neighbours see that help is needed,
    -- not the contents of private messages.
    role       TEXT NOT NULL CHECK (role IN ('family', 'community', 'neighbor')),
    created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS sessions (
    token      TEXT PRIMARY KEY,
    circle_id  INTEGER NOT NULL REFERENCES circle(id),
    created_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS events (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id   INTEGER NOT NULL REFERENCES devices(id),
    -- from the phone: peace | help | done | alert ; to the phone: message | ack
    kind        TEXT NOT NULL,
    direction   TEXT NOT NULL CHECK (direction IN ('from_device', 'to_device')),
    title       TEXT NOT NULL DEFAULT '',
    body        TEXT NOT NULL DEFAULT '',
    -- what the phone was doing when it asked for help; never shown to community/neighbour roles.
    context     TEXT NOT NULL DEFAULT '',
    created_at  REAL NOT NULL,
    created_by  INTEGER REFERENCES circle(id),
    status      TEXT NOT NULL DEFAULT 'new' CHECK (status IN ('new', 'claimed', 'closed')),
    claimed_by  INTEGER REFERENCES circle(id),
    claimed_at  REAL,
    delivered   INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS events_device_idx ON events (device_id, id);
"""


def connect() -> sqlite3.Connection:
    connection = sqlite3.connect(DB_PATH, check_same_thread=False)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA journal_mode=WAL")
    return connection


def init(path: str | None = None) -> None:
    global DB_PATH
    if path:
        DB_PATH = path
    with connect() as connection:
        connection.executescript(SCHEMA)


@contextmanager
def db() -> Iterator[sqlite3.Connection]:
    connection = connect()
    try:
        yield connection
        connection.commit()
    finally:
        connection.close()


def new_token() -> str:
    return secrets.token_urlsafe(24)


def new_pair_code() -> str:
    # Short enough to read off the elder's phone and type into a browser once.
    alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return "".join(secrets.choice(alphabet) for _ in range(6))


def create_device(elder_name: str) -> dict[str, Any]:
    with db() as connection:
        for _ in range(5):
            code = new_pair_code()
            try:
                cursor = connection.execute(
                    "INSERT INTO devices (token, pair_code, elder_name, created_at) VALUES (?,?,?,?)",
                    (new_token(), code, elder_name, time.time()),
                )
            except sqlite3.IntegrityError:
                continue
            row = connection.execute("SELECT * FROM devices WHERE id = ?", (cursor.lastrowid,)).fetchone()
            return dict(row)
    raise RuntimeError("could not allocate a pairing code")


def device_by_token(token: str) -> dict[str, Any] | None:
    with db() as connection:
        row = connection.execute("SELECT * FROM devices WHERE token = ?", (token,)).fetchone()
        return dict(row) if row else None


def device_by_pair_code(code: str) -> dict[str, Any] | None:
    with db() as connection:
        row = connection.execute(
            "SELECT * FROM devices WHERE pair_code = ?", (code.strip().upper(),)
        ).fetchone()
        return dict(row) if row else None


def touch_device(device_id: int, note: str = "") -> None:
    with db() as connection:
        connection.execute(
            "UPDATE devices SET last_seen_at = ?, last_seen_note = ? WHERE id = ?",
            (time.time(), note, device_id),
        )


def circle_of(device_id: int) -> list[dict[str, Any]]:
    with db() as connection:
        rows = connection.execute(
            "SELECT * FROM circle WHERE device_id = ? ORDER BY id", (device_id,)
        ).fetchall()
        return [dict(row) for row in rows]


def add_circle_member(device_id: int, name: str, phone: str, role: str) -> dict[str, Any]:
    with db() as connection:
        cursor = connection.execute(
            "INSERT INTO circle (device_id, name, phone, role, created_at) VALUES (?,?,?,?,?)",
            (device_id, name, phone, role, time.time()),
        )
        row = connection.execute("SELECT * FROM circle WHERE id = ?", (cursor.lastrowid,)).fetchone()
        return dict(row)


def create_session(circle_id: int) -> str:
    token = new_token()
    with db() as connection:
        connection.execute(
            "INSERT INTO sessions (token, circle_id, created_at) VALUES (?,?,?)",
            (token, circle_id, time.time()),
        )
    return token


def session_member(token: str) -> dict[str, Any] | None:
    with db() as connection:
        row = connection.execute(
            "SELECT c.*, d.elder_name, d.id AS device_id, d.last_seen_at, d.last_seen_note, "
            "d.pair_code FROM sessions s JOIN circle c ON c.id = s.circle_id "
            "JOIN devices d ON d.id = c.device_id WHERE s.token = ?",
            (token,),
        ).fetchone()
        return dict(row) if row else None


def add_event(
    device_id: int,
    kind: str,
    direction: str,
    title: str = "",
    body: str = "",
    context: str = "",
    created_by: int | None = None,
    status: str = "new",
) -> dict[str, Any]:
    with db() as connection:
        cursor = connection.execute(
            "INSERT INTO events (device_id, kind, direction, title, body, context, created_at, "
            "created_by, status) VALUES (?,?,?,?,?,?,?,?,?)",
            (device_id, kind, direction, title, body, context, time.time(), created_by, status),
        )
        row = connection.execute("SELECT * FROM events WHERE id = ?", (cursor.lastrowid,)).fetchone()
        return dict(row)


def events_for(device_id: int, limit: int = 50) -> list[dict[str, Any]]:
    with db() as connection:
        rows = connection.execute(
            "SELECT * FROM events WHERE device_id = ? ORDER BY id DESC LIMIT ?",
            (device_id, limit),
        ).fetchall()
        return [dict(row) for row in rows]


def pending_for_device(device_id: int) -> list[dict[str, Any]]:
    with db() as connection:
        rows = connection.execute(
            "SELECT * FROM events WHERE device_id = ? AND direction = 'to_device' AND delivered = 0 "
            "ORDER BY id",
            (device_id,),
        ).fetchall()
        return [dict(row) for row in rows]


def mark_delivered(ids: list[int]) -> None:
    if not ids:
        return
    with db() as connection:
        connection.executemany("UPDATE events SET delivered = 1 WHERE id = ?", [(i,) for i in ids])


def claim_event(event_id: int, circle_id: int) -> dict[str, Any] | None:
    with db() as connection:
        cursor = connection.execute(
            "UPDATE events SET status = 'claimed', claimed_by = ?, claimed_at = ? "
            "WHERE id = ? AND status = 'new'",
            (circle_id, time.time(), event_id),
        )
        if cursor.rowcount == 0:
            row = connection.execute("SELECT * FROM events WHERE id = ?", (event_id,)).fetchone()
            return dict(row) if row else None
        row = connection.execute("SELECT * FROM events WHERE id = ?", (event_id,)).fetchone()
        return dict(row) if row else None


def open_help_events(device_id: int) -> list[dict[str, Any]]:
    with db() as connection:
        rows = connection.execute(
            "SELECT * FROM events WHERE device_id = ? AND kind IN ('help','alert') "
            "AND status = 'new' ORDER BY id",
            (device_id,),
        ).fetchall()
        return [dict(row) for row in rows]


def member_by_id(member_id: int) -> dict[str, Any] | None:
    with db() as connection:
        row = connection.execute("SELECT * FROM circle WHERE id = ?", (member_id,)).fetchone()
        return dict(row) if row else None


def as_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False)
