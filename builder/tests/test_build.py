import json
import sys
from email.utils import formatdate
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import build  # noqa: E402
from fetcher import parse_feed  # noqa: E402
from rank import _matches, normalize_tokens  # noqa: E402
from translate import Translator  # noqa: E402

FIXTURE = Path(__file__).parent / "fixtures" / "sample_feed.xml"
NOW = formatdate(usegmt=True)


def feed(name, lang, region, *entries, category=None):
    xml = "<rss version='2.0'><channel><title>x</title>" + "".join(
        f"<item><title>{t}</title><link>https://{name}.test/{n}</link><description>{d}</description>"
        f"<pubDate>{NOW}</pubDate></item>" for n, (t, d) in enumerate(entries)) + "</channel></rss>"
    src = {"name": name, "url": "x", "lang": lang, "region": region, "weight": 1.0}
    if category:
        src["category"] = category
    return parse_feed(xml, src)


class FakeTranslator(Translator):
    """Dictionary 'translation' so tests never touch the network."""
    DICT = {
        "Central Bank of Egypt raises interest rates by 200 basis points": "البنك المركزي المصري يرفع أسعار الفائدة 200 نقطة أساس",
        "Ceasefire talks resume in Gaza": "استئناف محادثات وقف إطلاق النار في غزة",
        "Star striker scores twice in league match": "المهاجم النجم يسجل هدفين في مباراة الدوري",
        "Tech company launches new phone": "شركة تقنية تطلق هاتفا جديدا",
    }

    def __init__(self, tmp):
        super().__init__(tmp / "cache.json")
        self.calls = 0

    def _translate_chunk(self, texts):
        self.calls += 1
        return [self.DICT.get(t) for t in texts]


def test_parse_feed_cleans_and_drops_old():
    xml = FIXTURE.read_text().replace("PUBDATE", NOW)
    items = parse_feed(xml, {"name": "T", "url": "x", "lang": "en", "region": "egypt"})
    assert [i.url.rsplit("/", 1)[1] for i in items] == ["cbe-rates", "cbe-rates-2", "football"]
    assert "<p>" not in items[0].summary


def test_keyword_matching_respects_word_boundaries():
    assert _matches("the war in sudan", ["war"]) == ["war"]
    assert _matches("an award ceremony", ["war"]) == []
    assert _matches("storm damage", ["dam"]) == []
    assert _matches("وأعلن البنك المركزي", ["البنك المركزي"]) == ["البنك المركزي"]


def test_arabic_normalization_strips_prefixes():
    assert {"بنك", "مركزي"} <= normalize_tokens("والبنك المركزي")


def test_build_groups_ranks_and_filters(tmp_path):
    items = (
        feed("ahram", "en", "egypt",
             ("Central Bank of Egypt raises interest rates by 200 basis points", ""),
             ("Star striker scores twice in league match", ""),
             ("Tech company launches new phone", ""))
        + feed("youm7", "ar", "egypt", ("البنك المركزي المصري يرفع أسعار الفائدة بواقع 200 نقطة أساس", ""))
        + feed("bbc", "en", "world", ("Ceasefire talks resume in Gaza", ""))
    )
    tr = FakeTranslator(tmp_path)
    data = build.build(items, tr)
    stories = data["stories"]

    titles = [s["title"] for s in stories]
    assert not any("الدوري" in t or "هاتف" in t for t in titles), "sports / non-news must be dropped"
    assert len(stories) == 2

    top = stories[0]
    assert top["n_sources"] == 2 and top["category"] == "economy" and top["region"] == "egypt"
    assert top["importance"] >= 8 and top["tier"] in ("urgent", "important")
    gaza = stories[1]
    assert gaza["category"] == "politics" and gaza["region"] == "region"

    # Second run: everything comes from the cache, no translation requests.
    tr.save()
    tr2 = FakeTranslator(tmp_path)
    build.build(items, tr2)
    assert tr2.calls == 0
    json.dumps(data, ensure_ascii=False)


def test_untranslated_headlines_are_held_back(tmp_path):
    items = feed("bbc", "en", "world", ("Parliament votes on budget after election", ""))
    data = build.build(items, FakeTranslator(tmp_path))
    assert data["stories"] == [] and data["held_back"] == 1


def test_is_arabic():
    assert build.is_arabic("البنك المركزي يرفع الفائدة 200 نقطة")
    assert not build.is_arabic("Zelenskyy thanks Trump")
    assert not build.is_arabic("")
