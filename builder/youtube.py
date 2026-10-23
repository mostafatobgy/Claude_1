"""Most popular YouTube videos per country (YouTube Data API v3, free quota).

Needs the YOUTUBE_API_KEY secret; without it this section is simply skipped.
Each run costs 1 quota unit per country (the free quota is 10,000 units a day)."""
import logging
import os

import httpx

log = logging.getLogger(__name__)

API = "https://www.googleapis.com/youtube/v3/videos"
COUNTRIES = {  # code: (Arabic name, region)
    "EG": ("مصر", "egypt"), "SA": ("السعودية", "region"), "AE": ("الإمارات", "region"), "US": ("أمريكا", "world"),
}
PER_COUNTRY = 15
PER_REGION = 15
status = {"state": "not run", "errors": []}  # shown on the Actions run page


def parse_videos(data: dict, geo: str) -> list[dict]:
    out = []
    for v in data.get("items", []):
        sn, st = v.get("snippet", {}), v.get("statistics", {})
        thumbs = sn.get("thumbnails", {})
        thumb = (thumbs.get("medium") or thumbs.get("default") or {}).get("url", "")
        out.append({
            "id": v["id"], "geo": geo,
            "title": sn.get("title", "").strip(),
            "channel": sn.get("channelTitle", ""),
            "views": int(st.get("viewCount", 0) or 0),
            "published": sn.get("publishedAt", ""),
            "thumb": thumb,
            "url": f"https://www.youtube.com/watch?v={v['id']}",
        })
    return out


def fetch_youtube(client: httpx.Client | None = None, key: str | None = None) -> list[dict]:
    key = key if key is not None else os.environ.get("YOUTUBE_API_KEY", "").strip()
    if not key:
        log.info("YOUTUBE_API_KEY not set, YouTube trends disabled")
        status["state"] = "no key (YOUTUBE_API_KEY secret is empty or missing)"
        return []
    status["state"] = f"key present ({len(key)} chars)"
    client = client or httpx.Client(timeout=20)
    videos = []
    for geo in COUNTRIES:
        try:
            r = client.get(API, params={"part": "snippet,statistics", "chart": "mostPopular", "regionCode": geo,
                                        "maxResults": PER_COUNTRY, "hl": "ar", "key": key})
            r.raise_for_status()
            videos += parse_videos(r.json(), geo)
        except httpx.HTTPStatusError as exc:
            # Don't log the URL: it contains the key.
            log.warning("youtube %s failed: HTTP %s %s", geo, exc.response.status_code, exc.response.text[:200])
            try:
                reason = exc.response.json()["error"]["errors"][0]["reason"]
            except Exception:
                reason = exc.response.text[:80]
            status["errors"].append(f"{geo}: HTTP {exc.response.status_code} {reason}")
        except Exception as exc:
            log.warning("youtube %s failed: %s", geo, type(exc).__name__)
            status["errors"].append(f"{geo}: {type(exc).__name__}")
    log.info("fetched %d youtube videos", len(videos))
    return videos


def group_videos(videos: list[dict], arabic: dict[str, str]) -> list[dict]:
    """One entry per video per region (a video popular in SA and AE is listed once), most viewed first."""
    merged: dict[tuple, dict] = {}
    for v in videos:
        name, region = COUNTRIES[v["geo"]]
        key = (region, v["id"])
        if key in merged:
            merged[key]["countries"].append(name)
            continue
        merged[key] = {**{k: v[k] for k in ("title", "channel", "views", "published", "thumb", "url")},
                       "title_ar": arabic.get(v["title"], v["title"]), "region": region, "countries": [name]}
    out = []
    for region in ("egypt", "region", "world"):
        group = sorted((m for m in merged.values() if m["region"] == region),
                       key=lambda m: (-len(m["countries"]), -m["views"]))
        out += group[:PER_REGION]
    return out
