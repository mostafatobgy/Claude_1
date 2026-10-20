import sys
from datetime import date
from pathlib import Path

import httpx

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import wiki  # noqa: E402


def test_pick_rising_skips_evergreen_and_special_pages():
    latest = {"الصفحة_الرئيسية": 900_000, "خاص:بحث": 50_000, "مصر": 20_000, "زلزال_ما": 40_000, "لاعب_جديد": 9_000}
    previous = [{"مصر": 19_000, "زلزال_ما": 1_000, "x": 5_000}, {"مصر": 21_000, "x": 5_000}]
    out = wiki.pick_rising(latest, previous)
    assert [a["title"] for a in out] == ["زلزال ما", "لاعب جديد"]   # مصر is always read this much
    assert out[0]["growth"] > 10


def test_fetch_falls_back_a_day_and_builds_links(monkeypatch):
    def handler(req):
        path = req.url.path
        if "/top/" in path:
            if path.endswith("2026/10/19"):
                return httpx.Response(404)
            views = {"2026/10/18": {"حدث_كبير": 30_000}, }.get(path[-10:], {"عادي": 100})
            return httpx.Response(200, json={"items": [{"articles": [
                {"article": k, "views": v} for k, v in views.items()]}]})
        return httpx.Response(200, json={"extract": "نص قصير عن الحدث."})
    client = httpx.Client(transport=httpx.MockTransport(handler))
    monkeypatch.setattr(wiki.time, "sleep", lambda s: None)
    data = wiki.fetch_wiki(client, today=date(2026, 10, 20))
    assert data["day"] == "2026-10-18"
    a = data["articles"][0]
    assert a["title"] == "حدث كبير" and a["summary"] == "نص قصير عن الحدث."
    assert a["url"].startswith("https://ar.wikipedia.org/wiki/%D8")
