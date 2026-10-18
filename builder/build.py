"""Build site/news.json: fetch feeds -> translate -> group -> rank.  Run: python build.py"""
import asyncio
import json
import logging
import time
from pathlib import Path

from fetcher import fetch_all
from rank import classify_and_score, group_stories, tier
from translate import Translator

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("build")

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "site" / "news.json"
CACHE = Path(__file__).resolve().parent / "translations.json"
MAX_STORIES = 200


def build(items, translator) -> dict:
    foreign = [t for i in items if i.source_lang != "ar" for t in (i.title, i.summary)]
    arabic = translator.translate_many(foreign)
    stories = [s for s in group_stories(items, arabic) if classify_and_score(s)]
    stories.sort(key=lambda s: s.score, reverse=True)
    out = []
    for n, s in enumerate(stories[:MAX_STORIES]):
        links = sorted(({"source": i.source, "url": i.url, "published": i.published} for i in s.items),
                       key=lambda l: l["published"])
        n_sources = len({l["source"] for l in links})
        out.append({
            "id": n,
            "title": s.title_ar,
            "summary": s.summary_ar[:400],
            "category": s.category,
            "region": s.region,
            "importance": s.importance,
            "tier": tier(s.importance, n_sources),
            "n_sources": n_sources,
            "published": links[0]["published"],
            "score": s.score,
            "keywords": s.keywords,
            "sources": links,
        })
    return {"updated": int(time.time()), "stories": out}


def main():
    items = asyncio.run(fetch_all())
    log.info("fetched %d articles", len(items))
    translator = Translator(CACHE)
    data = build(items, translator)
    translator.save()
    OUT.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    log.info("wrote %d stories to %s", len(data["stories"]), OUT)


if __name__ == "__main__":
    main()
