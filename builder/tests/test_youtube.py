import sys
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import youtube  # noqa: E402


def video(vid, title, views):
    return {"id": vid, "snippet": {"title": title, "channelTitle": "قناة", "publishedAt": "2026-10-22T10:00:00Z",
                                   "thumbnails": {"medium": {"url": f"https://i.ytimg.com/{vid}.jpg"}}},
            "statistics": {"viewCount": str(views)}}


def test_disabled_without_key(monkeypatch):
    monkeypatch.delenv("YOUTUBE_API_KEY", raising=False)
    assert youtube.fetch_youtube() == []


def test_fetch_group_and_translate():
    data = {"EG": [video("a", "أغنية جديدة", 900_000), video("b", "مباراة", 2_000_000)],
            "SA": [video("c", "مقطع", 100_000)], "AE": [video("c", "مقطع", 100_000), video("d", "آخر", 5_000_000)],
            "US": [video("e", "Big launch", 3_000_000)]}
    seen_keys = []

    def handler(req):
        seen_keys.append(req.url.params["key"])
        geo = req.url.params["regionCode"]
        return httpx.Response(200, json={"items": data[geo]})

    vids = youtube.fetch_youtube(httpx.Client(transport=httpx.MockTransport(handler)), key="k")
    assert set(seen_keys) == {"k"} and len(vids) == 6
    out = youtube.group_videos(vids, {"Big launch": "إطلاق كبير"})
    egypt = [v for v in out if v["region"] == "egypt"]
    assert [v["title"] for v in egypt] == ["مباراة", "أغنية جديدة"]
    region = [v for v in out if v["region"] == "region"]
    assert region[0]["title"] == "مقطع" and region[0]["countries"] == ["السعودية", "الإمارات"]
    world = [v for v in out if v["region"] == "world"][0]
    assert world["title_ar"] == "إطلاق كبير" and world["url"].endswith("v=e")


def test_api_error_is_skipped():
    client = httpx.Client(transport=httpx.MockTransport(lambda req: httpx.Response(403, text="quotaExceeded")))
    assert youtube.fetch_youtube(client, key="k") == []
