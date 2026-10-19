"""Strong search trends from Google Trends' public RSS (free, no key), per country, grouped by region."""
import logging
import re
import xml.etree.ElementTree as ET
from email.utils import parsedate_to_datetime

import httpx

log = logging.getLogger(__name__)

COUNTRIES = {  # code: (Arabic name, region)
    "EG": ("مصر", "egypt"),
    "SA": ("السعودية", "region"), "AE": ("الإمارات", "region"), "MA": ("المغرب", "region"), "JO": ("الأردن", "region"),
    "US": ("أمريكا", "world"), "GB": ("بريطانيا", "world"),
}
URLS = ["https://trends.google.com/trending/rss?geo={geo}",
        "https://trends.google.com/trends/trendingsearches/daily/rss?geo={geo}"]
PER_REGION = 20


def parse_traffic(text: str) -> int:
    """'200K+' -> 200000, '2,000+' -> 2000, '1M+' -> 1000000."""
    m = re.match(r"\s*([\d.,]+)\s*([KkMm]?)", text or "")
    if not m:
        return 0
    n = float(m.group(1).replace(",", ""))
    return int(n * {"k": 1_000, "m": 1_000_000}.get(m.group(2).lower(), 1))


def _ht(el, name):
    """Find an ht: child whatever namespace URI the feed declares for the ht prefix."""
    for child in el:
        if child.tag.endswith("}" + name) or child.tag == name:
            return child
    return None


def _children(el, name):
    return [c for c in el if c.tag.endswith("}" + name) or c.tag == name]


def parse_trends(xml: bytes | str, geo: str) -> list[dict]:
    root = ET.fromstring(xml)
    out = []
    for item in root.iter("item"):
        term = (item.findtext("title") or "").strip()
        if not term:
            continue
        traffic_el = _ht(item, "approx_traffic")
        try:
            started = int(parsedate_to_datetime(item.findtext("pubDate")).timestamp())
        except (TypeError, ValueError):
            started = 0
        news = []
        for n in _children(item, "news_item"):
            title, url = _ht(n, "news_item_title"), _ht(n, "news_item_url")
            src = _ht(n, "news_item_source")
            if title is not None and url is not None and title.text and url.text:
                news.append({"title": re.sub(r"<[^>]+>", "", title.text).strip(), "url": url.text.strip(),
                             "source": (src.text or "").strip() if src is not None else ""})
        out.append({"term": term, "geo": geo, "traffic": parse_traffic(traffic_el.text if traffic_el is not None else ""),
                    "started": started, "news": news[:3]})
    return out


def fetch_trends(client: httpx.Client | None = None) -> list[dict]:
    client = client or httpx.Client(timeout=20, follow_redirects=True,
                                    headers={"User-Agent": "Mozilla/5.0 (compatible; AkhbarBot/1.0)"})
    items = []
    for geo in COUNTRIES:
        for url in URLS:
            try:
                r = client.get(url.format(geo=geo))
                r.raise_for_status()
                got = parse_trends(r.content, geo)
                if got:
                    items += got
                    break
            except Exception as exc:
                log.warning("trends %s failed (%s): %s", geo, url, exc)
    log.info("fetched %d trends", len(items))
    return items


def group_trends(items: list[dict], arabic: dict[str, str]) -> list[dict]:
    """Merge the same search term across countries of one region; rank by total searches."""
    merged: dict[tuple, dict] = {}
    for it in items:
        name, region = COUNTRIES[it["geo"]]
        key = (region, it["term"].strip().lower())
        t = merged.get(key)
        if t is None:
            t = merged[key] = {"term": it["term"], "region": region, "countries": [], "traffic": 0,
                               "started": it["started"], "news": []}
        t["countries"].append(name)
        t["traffic"] += it["traffic"]
        t["started"] = min(t["started"], it["started"]) if t["started"] and it["started"] else t["started"] or it["started"]
        seen = {n["url"] for n in t["news"]}
        t["news"] += [n for n in it["news"] if n["url"] not in seen]

    out = []
    for region in ("egypt", "region", "world"):
        group = sorted((t for t in merged.values() if t["region"] == region),
                       key=lambda t: (-len(t["countries"]), -t["traffic"]))[:PER_REGION]
        for t in group:
            t["term_ar"] = arabic.get(t["term"], t["term"])
            t["news"] = [{**n, "title": arabic.get(n["title"], n["title"])} for n in t["news"][:2]]
            out.append(t)
    return out
