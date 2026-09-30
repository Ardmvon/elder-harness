"""The trusted-circle server.

Three jobs, in the order they matter:

1. **Keep the circle reachable.** The phone reports what happened; the people who care can see it and
   take it over from a web page, with no app to install.
2. **Watch the watcher.** A phone that goes quiet cannot say so. That is the one thing only a server
   can notice (see [watch]).
3. **Keep "trusted" meaningful.** Who is in the circle, what each role may see, and that instructions
   from anyone outside it count for nothing.

Deliberately small: SQLite, no ORM, no background queue, no accounts-and-passwords. The elder's phone
authenticates with a device token; a family member joins once with the pairing code shown on that
phone and keeps a session cookie.
"""

from __future__ import annotations

import logging
import os
import time
from contextlib import asynccontextmanager
from typing import Any

from fastapi import Body, Cookie, FastAPI, Form, Header, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

from . import db, notify, speech, watch, web

log = logging.getLogger("hotline")
logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

# How often the phone is expected to check in, and how long silence is tolerated before the circle
# is told. Overridable so tests (and a demo) do not have to wait hours.
HEARTBEAT_SECONDS = int(os.environ.get("HOTLINE_HEARTBEAT_SECONDS", "300"))
SILENCE_SECONDS = int(os.environ.get("HOTLINE_SILENCE_SECONDS", str(watch.DEFAULT_SILENCE_SECONDS)))


@asynccontextmanager
async def lifespan(app: FastAPI):
    import asyncio

    # Credentials for the model and for speech live here, never in the phone.
    from . import load_env

    load_env(os.path.join(os.path.dirname(__file__), "..", ".env"))
    db.init()
    task = asyncio.create_task(watch.run_forever(interval_seconds=300, silence_seconds=SILENCE_SECONDS))
    try:
        yield
    finally:
        task.cancel()


app = FastAPI(title="银龄专线 · 家人与社区", lifespan=lifespan)


# --------------------------------------------------------------------------- device (the phone)


def device_from_auth(authorization: str | None) -> dict[str, Any]:
    token = (authorization or "").removeprefix("Bearer ").strip()
    device = db.device_by_token(token) if token else None
    if not device:
        raise HTTPException(status_code=401, detail="设备未配对或令牌无效")
    return device


@app.get("/api/health")
def health() -> dict[str, Any]:
    return {"ok": True, "server_time": time.time(), "heartbeat_seconds": HEARTBEAT_SECONDS}


@app.post("/api/device/pair")
def pair(payload: dict[str, Any] = Body(default={})) -> dict[str, Any]:
    """Called once when the family sets the phone up. Returns the token the phone keeps."""
    elder_name = str(payload.get("elder_name", "")).strip()[:40]
    device = db.create_device(elder_name)
    db.touch_device(device["id"], "刚刚配对")
    log.info("[pair] device=%s elder=%r", device["id"], elder_name)
    return {
        "device_id": device["id"],
        "token": device["token"],
        "pair_code": device["pair_code"],
        "heartbeat_seconds": HEARTBEAT_SECONDS,
    }


@app.post("/api/device/heartbeat")
def heartbeat(
    payload: dict[str, Any] = Body(default={}),
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    """Proof of life, and the phone's inbox: whoever is running is also who gets told things."""
    device = device_from_auth(authorization)
    db.touch_device(device["id"], str(payload.get("note", ""))[:200])
    # Delivered is set by the phone's /ack call, not here: handing a message to the phone and
    # displaying it are two different things, and a crash between them must not eat the message.
    pending = db.pending_for_device(device["id"])
    sender_names = {member["id"]: member["name"] for member in db.circle_of(device["id"])}
    return {
        "ok": True,
        "server_time": time.time(),
        "pending": [
            {
                "id": event["id"],
                "kind": event["kind"],
                "title": event["title"],
                "body": event["body"],
                "from": sender_names.get(event["created_by"], ""),
            }
            for event in pending
        ],
    }


@app.post("/api/device/ack")
def acknowledge(
    payload: dict[str, Any] = Body(default={}),
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    """The phone confirms it displayed a message, or that the elder read it.

    Only this device's own to_device events can be acknowledged, so one paired phone cannot mark
    another phone's inbox as read.
    """
    device = device_from_auth(authorization)
    raw_ids = payload.get("ids", [])
    if not isinstance(raw_ids, list):
        raise HTTPException(status_code=422, detail="ids 必须是数组")
    ids: list[int] = []
    for value in raw_ids[:100]:
        try:
            ids.append(int(value))
        except (TypeError, ValueError):
            continue
    read = bool(payload.get("read", False))
    acked = db.acknowledge_events(device["id"], ids, read=read)
    log.info("[ack] device=%s read=%s ids=%s", device["id"], read, acked)
    return {"ok": True, "read": read, "acked": acked}


@app.get("/api/speech/status")
def speech_status() -> dict[str, Any]:
    """Whether this server can turn speech into text; the phone asks before offering to listen."""
    return {"configured": speech.is_configured(), "style": speech.style()}


@app.post("/api/device/transcribe")
async def transcribe(
    request: Request,
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    """Raw 16 kHz mono PCM16 in the body, text out. The phone records; the server holds the keys."""
    device = device_from_auth(authorization)
    pcm = await request.body()
    if len(pcm) < speech.SAMPLE_RATE:  # under ~30ms of audio is a mis-tap, not a sentence
        return {"text": ""}
    try:
        text = await speech.transcribe(pcm, keyterms=[device["elder_name"]] if device["elder_name"] else None)
    except speech.SpeechError as error:
        log.warning("[speech] device=%s 失败：%s", device["id"], error)
        raise HTTPException(status_code=503, detail=str(error)) from error
    log.info("[speech] device=%s %d 字节 → %r", device["id"], len(pcm), text[:40])
    return {"text": text}


@app.post("/api/device/events")
def post_event(
    payload: dict[str, Any] = Body(default={}),
    authorization: str | None = Header(default=None),
) -> dict[str, Any]:
    """The phone's news: 报平安 / 求助 / 办好了 / 异常."""
    device = device_from_auth(authorization)
    kind = str(payload.get("kind", "")).strip()
    if kind not in {"peace", "help", "done", "alert"}:
        raise HTTPException(status_code=422, detail="未知的事件类型")
    title = str(payload.get("title", "")).strip()[:120]
    body = str(payload.get("body", "")).strip()[:1000]
    context = str(payload.get("context", "")).strip()[:2000]
    event = db.add_event(device["id"], kind, "from_device", title=title, body=body, context=context)
    if kind in {"help", "alert"}:
        notify.notify_circle(db.circle_of(device["id"]), kind, title, body)
    log.info("[event] device=%s kind=%s title=%r", device["id"], kind, title)
    return {"id": event["id"], "created_at": event["created_at"]}


# --------------------------------------------------------------------------- the circle (web)


def member_from_cookie(session: str | None) -> dict[str, Any] | None:
    return db.session_member(session) if session else None


@app.get("/", response_class=HTMLResponse)
def index(session: str | None = Cookie(default=None)):
    if member_from_cookie(session):
        return RedirectResponse("/family", status_code=303)
    return HTMLResponse(web.join_page())


@app.post("/join")
def join(
    pair_code: str = Form(...),
    name: str = Form(...),
    phone: str = Form(default=""),
    role: str = Form(default="family"),
):
    device = db.device_by_pair_code(pair_code)
    if not device:
        return HTMLResponse(web.join_page("配对码不对，或者老人手机上还没配对成功。", pair_code), status_code=400)
    if role not in web.ROLE_LABEL:
        role = "family"
    clean_name = name.strip()[:24] or "家人"
    clean_phone = phone.strip()[:20]
    # Joining twice with the same number updates that person instead of adding a duplicate.
    existing = next(
        (member for member in db.circle_of(device["id"]) if clean_phone and member["phone"] == clean_phone),
        None,
    )
    member = existing or db.add_circle_member(device["id"], clean_name, clean_phone, role)
    session = db.create_session(member["id"])
    log.info("[join] device=%s member=%s role=%s", device["id"], clean_name, role)
    response = RedirectResponse("/family", status_code=303)
    response.set_cookie("session", session, httponly=True, samesite="lax", max_age=60 * 60 * 24 * 365)
    return response


@app.get("/family", response_class=HTMLResponse)
def family(
    session: str | None = Cookie(default=None),
    ok: str = "",
):
    member = member_from_cookie(session)
    if not member:
        return RedirectResponse("/", status_code=303)
    with db.db() as connection:
        row = connection.execute("SELECT * FROM devices WHERE id = ?", (member["device_id"],)).fetchone()
    device = dict(row)
    return HTMLResponse(
        web.dashboard(
            member,
            device,
            db.events_for(device["id"], limit=80),
            db.circle_of(device["id"]),
            SILENCE_SECONDS,
            flash=ok,
        )
    )


@app.post("/family/claim/{event_id}")
def claim(event_id: int, session: str | None = Cookie(default=None)):
    member = member_from_cookie(session)
    if not member:
        return RedirectResponse("/", status_code=303)
    event = db.claim_event(event_id, member["id"])
    if not event or event["device_id"] != member["device_id"]:
        return RedirectResponse("/family?ok=这条求助已经有人接手了", status_code=303)
    db.add_event(
        member["device_id"],
        "ack",
        "to_device",
        title=f"{member['name']}说她来处理",
        body="等一下，我来帮你。",
        created_by=member["id"],
    )
    log.info("[claim] event=%s by=%s", event_id, member["name"])
    return RedirectResponse("/family?ok=你已经接手，老人手机上会收到提示", status_code=303)


@app.post("/family/message")
def message(
    text: str = Form(...),
    session: str | None = Cookie(default=None),
):
    member = member_from_cookie(session)
    if not member:
        return RedirectResponse("/", status_code=303)
    # Only family may put words on the elder's phone: that channel reads as a familiar voice, and it
    # must not be usable by someone the person does not know.
    if member["role"] != "family":
        raise HTTPException(status_code=403, detail="只有家人能给老人留话")
    body = text.strip()[:500]
    if body:
        db.add_event(
            member["device_id"], "message", "to_device", title="留言", body=body, created_by=member["id"]
        )
    return RedirectResponse("/family?ok=已经发到老人手机上", status_code=303)


@app.post("/api/watch/check")
def trigger_watch(authorization: str | None = Header(default=None)) -> dict[str, Any]:
    """For tests and for a cron-style deployment; the background task calls the same function."""
    raised = watch.check_silence(silence_seconds=SILENCE_SECONDS)
    return {"raised": [event["title"] for event in raised]}


@app.get("/api/devices/{device_id}/summary")
def summary(device_id: int) -> JSONResponse:
    """A small read-only view, useful while building the app side."""
    with db.db() as connection:
        row = connection.execute("SELECT * FROM devices WHERE id = ?", (device_id,)).fetchone()
    if not row:
        raise HTTPException(status_code=404, detail="没有这个设备")
    device = dict(row)
    return JSONResponse(
        {
            "elder_name": device["elder_name"],
            "last_seen_at": device["last_seen_at"],
            "circle": db.circle_of(device_id),
            "events": db.events_for(device_id, limit=20),
        }
    )
