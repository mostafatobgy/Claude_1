"""Rising articles on Arabic Wikipedia (Wikimedia's free pageviews API, no key).

"Rising" = read much more yesterday than on the days before, so evergreen pages that are always
on top (countries, religions, the main page) don't crowd out what people are curious about now."""
import logging
import time
from datetime import date, timedelta
from urllib.parse import quote

import httpx

log = logging.getLogger(__name__)

PROJECT = "ar.wikipedia"
TOP_URL = "https://wikimedia.org/api/rest_v1/metrics/pageviews/top/{project}/all-access/{d:%Y/%m/%d}"
SUMMARY_URL = "https://ar.wikipedia.org/api/rest_v1/page/summary/{title}"
HEADERS = {"User-Agent": "AkhbarBot/1.0 (https://github.com/mostafatobgy/Claude_1)"}
MAX_ITEMS = 15
MIN_GROWTH = 1.8
BASELINE_DAYS = 3
# Namespaces and fixed pages that are never "news" (main page, search, categories, files, ...).
SKIP_PREFIXES = ("خاص:", "ويكيبيديا:", "تصنيف:", "ملف:", "بوابة:", "مساعدة:", "قالب:", "نقاش:", "مستخدم:",
                 "Special:", "Wikipedia:", "File:")
SKIP_TITLES = {"الصفحة_الرئيسية", "Main_Page", "-"}


def _top(client: httpx.Client, day: date) -> dict[str, int] | None:
    r = client.get(TOP_URL.format(project=PROJECT, d=day))
    if r.status_code == 404:  # that day isn't published yet
        return None
    r.raise_for_status()
    return {a["article"]: a["views"] for a in r.json()["items"][0]["articles"]}


def pick_rising(latest: dict[str, int], previous: list[dict[str, int]]) -> list[dict]:
    """Articles whose views jumped versus the average of the previous days."""
    out = []
    for title, views in latest.items():
        if title in SKIP_TITLES or title.startswith(SKIP_PREFIXES):
            continue
        # Absent from an earlier top list means it was below that list's lowest entry.
        base = [p.get(title, min(p.values()) if p else 0) for p in previous]
        baseline = max(1, sum(base) / len(base)) if base else 1
        growth = views / baseline
        if growth >= MIN_GROWTH:
            out.append({"title": title.replace("_", " "), "page": title, "views": views,
                        "growth": round(growth, 1)})
    out.sort(key=lambda a: -a["views"])
    return out[:MAX_ITEMS]


def _summary(client: httpx.Client, page: str) -> str:
    try:
        r = client.get(SUMMARY_URL.format(title=quote(page, safe="")))
        r.raise_for_status()
        text = r.json().get("extract", "")
        return text[:220] + ("…" if len(text) > 220 else "")
    except Exception:
        return ""


def fetch_wiki(client: httpx.Client | None = None, today: date | None = None) -> dict:
    client = client or httpx.Client(timeout=20, headers=HEADERS, follow_redirects=True)
    today = today or date.today()
    try:
        # Yesterday's data usually appears during the day; fall back one day if it isn't out yet.
        for lag in (1, 2):
            latest_day = today - timedelta(days=lag)
            latest = _top(client, latest_day)
            if latest:
                break
        else:
            return {"day": "", "articles": []}
        previous = [p for k in range(1, BASELINE_DAYS + 1)
                    if (p := _top(client, latest_day - timedelta(days=k)))]
    except Exception as exc:
        log.warning("wikipedia trends failed: %s", exc)
        return {"day": "", "articles": []}

    articles = pick_rising(latest, previous)
    for a in articles:
        a["summary"] = _summary(client, a["page"])
        a["url"] = "https://ar.wikipedia.org/wiki/" + quote(a.pop("page"), safe="")
        time.sleep(0.1)
    log.info("wikipedia rising articles: %d (day %s)", len(articles), latest_day)
    return {"day": latest_day.isoformat(), "articles": articles}
