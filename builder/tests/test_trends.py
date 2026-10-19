import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import trends  # noqa: E402

FIXTURE = (Path(__file__).parent / "fixtures" / "trends_eg.xml").read_bytes()


def test_parse_traffic():
    assert trends.parse_traffic("200K+") == 200_000
    assert trends.parse_traffic("2,000+") == 2_000
    assert trends.parse_traffic("1M+") == 1_000_000
    assert trends.parse_traffic("") == 0


def test_parse_feed():
    items = trends.parse_trends(FIXTURE, "EG")
    assert [i["term"] for i in items] == ["الأهلي", "سعر الدولار"]
    assert items[0]["traffic"] == 200_000 and items[0]["started"] > 0
    assert items[0]["news"][0] == {"title": "الأهلي يفوز في الدوري", "url": "https://a.test/1", "source": "اليوم السابع"}
    assert items[1]["news"] == []


def test_group_merges_countries_and_translates():
    raw = [
        {"term": "Real Madrid", "geo": "SA", "traffic": 50_000, "started": 100, "news": []},
        {"term": "real madrid", "geo": "AE", "traffic": 20_000, "started": 90, "news": []},
        {"term": "Big local thing", "geo": "JO", "traffic": 500_000, "started": 100, "news": []},
        {"term": "Fed", "geo": "US", "traffic": 100_000, "started": 100,
         "news": [{"title": "Fed holds rates", "url": "u", "source": "s"}]},
    ]
    out = trends.group_trends(raw, {"Real Madrid": "ريال مدريد", "Fed holds rates": "الفيدرالي يثبت الفائدة"})
    region = [t for t in out if t["region"] == "region"]
    assert region[0]["term_ar"] == "ريال مدريد" and region[0]["countries"] == ["السعودية", "الإمارات"]
    assert region[0]["traffic"] == 70_000 and region[0]["started"] == 90
    world = [t for t in out if t["region"] == "world"][0]
    assert world["news"][0]["title"] == "الفيدرالي يثبت الفائدة"
