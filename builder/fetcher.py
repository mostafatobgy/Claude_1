"""Fetch and normalize RSS/Atom feeds."""
import asyncio
import calendar
import hashlib
import html
import json
import logging
import re
import time
from dataclasses import dataclass
from pathlib import Path

import feedparser
import httpx

log = logging.getLogger(__name__)

SOURCES_FILE = Path(__file__).with_name("sources.json")
MAX_AGE_SECONDS = 36 * 3600
MAX_ITEMS_PER_FEED = 25
_TAG_RE = re.compile(r"<[^>]+>")


@dataclass
class RawItem:
    uid: str
    source: str
    source_lang: str
    source_region: str
    source_weight: float
    source_category: str | None
    url: str
    title: str
    summary: str
    published: int  # unix seconds


def load_sources() -> list[dict]:
    return json.loads(SOURCES_FILE.read_text(encoding="utf-8"))


def _clean(text: str, limit: int) -> str:
    text = html.unescape(_TAG_RE.sub(" ", text or ""))
    return re.sub(r"\s+", " ", text).strip()[:limit]


def url_uid(url: str) -> str:
    return hashlib.sha1(url.strip().encode()).hexdigest()[:16]


def parse_feed(content: bytes | str, source: dict, now: float | None = None) -> list[RawItem]:
    now = now or time.time()
    feed = feedparser.parse(content)
    items = []
    for entry in feed.entries[:MAX_ITEMS_PER_FEED]:
        link = entry.get("link")
        title = _clean(entry.get("title", ""), 300)
        if not link or not title:
            continue
        ts = entry.get("published_parsed") or entry.get("updated_parsed")
        published = int(calendar.timegm(ts)) if ts else int(now)
        if now - published > MAX_AGE_SECONDS:
            continue
        items.append(RawItem(
            uid=url_uid(link),
            source=source["name"],
            source_lang=source.get("lang", "en"),
            source_region=source.get("region", "world"),
            source_weight=float(source.get("weight", 1.0)),
            source_category=source.get("category"),
            url=link,
            title=title,
            summary=_clean(entry.get("summary", ""), 600),
            published=min(published, int(now)),
        ))
    return items


async def _fetch_one(client: httpx.AsyncClient, source: dict) -> list[RawItem]:
    try:
        resp = await client.get(source["url"])
        resp.raise_for_status()
        return parse_feed(resp.content, source)
    except Exception as exc:  # one broken feed must not stop the others
        log.warning("feed failed: %s (%s): %s", source["name"], source["url"], exc)
        return []


async def fetch_all(sources: list[dict] | None = None) -> list[RawItem]:
    sources = sources or load_sources()
    headers = {"User-Agent": "Mozilla/5.0 (compatible; AkhbarBot/1.0)"}
    async with httpx.AsyncClient(timeout=20, follow_redirects=True, headers=headers) as client:
        results = await asyncio.gather(*(_fetch_one(client, s) for s in sources))
    seen, items = set(), []
    for item in (i for batch in results for i in batch):
        if item.uid not in seen:
            seen.add(item.uid)
            items.append(item)
    return items
