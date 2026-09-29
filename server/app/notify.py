"""Telling the circle that something happened.

M1 only writes to the log and to the event stream: sending real SMS needs a provider, a registered
signature and an approved template (a few days of paperwork in China), and the decision was to get
the protocol and the web page right first. The call site is already shaped for it — one function,
one place to add the provider.
"""

from __future__ import annotations

import logging
from typing import Any

log = logging.getLogger("hotline.notify")

# What each role is expected to hear about.
ROLE_SCOPE = {
    "family": {"peace", "help", "done", "alert"},
    "community": {"help", "alert"},
    "neighbor": {"help"},
}


def recipients(members: list[dict[str, Any]], kind: str) -> list[dict[str, Any]]:
    return [member for member in members if kind in ROLE_SCOPE.get(member["role"], set())]


def notify_circle(members: list[dict[str, Any]], kind: str, title: str, body: str) -> list[str]:
    """Returns the phone numbers that *would* be texted. Replaced by a provider in M2."""
    targets = recipients(members, kind)
    for member in targets:
        log.info("[notify] → %s(%s) %s | %s | %s", member["name"], member["role"], member["phone"], title, body)
    if not targets:
        log.info("[notify] nobody in the circle wants %s", kind)
    return [member["phone"] for member in targets if member["phone"]]
