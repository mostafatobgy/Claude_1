"""Push alerts for new top-tier stories via ntfy.sh (free; Android app "ntfy").

The topic name comes from the NTFY_TOPIC secret, so nobody else can read or spam it.
Sent stories are remembered in notified.json (kept between runs by the Actions cache)."""
import hashlib
import json
import logging
import os
import time
from pathlib import Path

import httpx

log = logging.getLogger(__name__)

STATE = Path(__file__).resolve().parent / "notified.json"
MAX_PER_RUN = 3
MAX_PER_DAY = 12
MAX_AGE_HOURS = 3      # don't alert about stories that broke long ago
KEEP_DAYS = 3


def story_key(story: dict) -> str:
    """Stable across runs: the earliest source link of the story."""
    return hashlib.sha1(story["sources"][0]["url"].encode()).hexdigest()[:16]


def pick_new_alerts(stories: list[dict], sent: dict[str, int], now: float) -> list[dict]:
    fresh = [s for s in stories
             if s["tier"] == "urgent"
             and now - s["published"] <= MAX_AGE_HOURS * 3600
             and story_key(s) not in sent]
    return sorted(fresh, key=lambda s: -s["score"])[:MAX_PER_RUN]


def send(client: httpx.Client, topic: str, story: dict, site_url: str):
    payload = {
        "topic": topic,
        "title": f"🔴 عاجل · {story['title']}",
        "message": story["summary"][:250] or f"نشرته {story['n_sources']} مصادر",
        "priority": 4,
        "click": site_url,
        "actions": [{"action": "view", "label": "المصدر", "url": story["sources"][-1]["url"]}],
    }
    r = client.post("https://ntfy.sh/", json=payload, timeout=15)
    r.raise_for_status()


def notify(stories: list[dict], client: httpx.Client | None = None, state_path: Path = STATE) -> int:
    topic = os.environ.get("NTFY_TOPIC", "").strip()
    if not topic:
        log.info("NTFY_TOPIC not set, alerts disabled")
        return 0
    now = time.time()
    try:
        state = json.loads(state_path.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        state = {}
    sent: dict[str, int] = state.get("seen", {})
    sent_times = [t for t in state.get("sent_times", []) if now - t < 86400]
    budget = max(0, MAX_PER_DAY - len(sent_times))

    client = client or httpx.Client()
    site_url = os.environ.get("SITE_URL", "")
    count, failed = 0, set()
    for story in pick_new_alerts(stories, sent, now)[:budget]:
        try:
            send(client, topic, story, site_url)
            count += 1
            sent_times.append(int(now))
        except Exception as exc:
            log.warning("alert failed: %s", exc)
            failed.add(story_key(story))
    # Mark the other current urgent stories as seen too (beyond the cap, or too old), so a burst of
    # breaking news gives at most MAX_PER_RUN alerts. Failed sends are retried next run.
    for s in stories:
        if s["tier"] == "urgent" and story_key(s) not in failed:
            sent.setdefault(story_key(s), int(now))
    sent = {k: v for k, v in sent.items() if now - v < KEEP_DAYS * 86400}
    state_path.write_text(json.dumps({"seen": sent, "sent_times": sent_times}))
    log.info("alerts sent: %d", count)
    return count
