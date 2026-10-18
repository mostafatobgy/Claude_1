"""Build site/news.json: fetch feeds -> translate -> group -> rank.  Run: python build.py"""
import asyncio
import json
import logging
import re
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
_ARABIC = re.compile(r"[\u0600-\u06FF]")


def is_arabic(text: str) -> bool:
    letters = re.findall(r"[^\W\d_]", text)
    return bool(letters) and len(_ARABIC.findall(text)) >= len(letters) / 2


def build(items, translator) -> dict:
    # Newest headlines first, then summaries: if translation gets cut off, the headlines are done.
    foreign = sorted((i for i in items if i.source_lang != "ar"), key=lambda i: -i.published)
    arabic = translator.translate_many([i.title for i in foreign] + [i.summary for i in foreign])
    stories = [s for s in group_stories(items, arabic) if classify_and_score(s)]
    stories.sort(key=lambda s: s.score, reverse=True)
    out, held_back = [], 0
    for s in stories:
        if len(out) >= MAX_STORIES:
            break
        if not is_arabic(s.title_ar):
            # Another outlet in the same story may have an Arabic headline; otherwise wait for the next run.
            alt = next((arabic.get(i.title, i.title) for i in s.items if is_arabic(arabic.get(i.title, i.title))), None)
            if not alt:
                held_back += 1
                continue
            s.title_ar = alt
        if not is_arabic(s.summary_ar):
            s.summary_ar = next((arabic.get(i.summary, i.summary) for i in s.items
                                 if is_arabic(arabic.get(i.summary, i.summary))), "")
        n = len(out)
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
    log.info("stories: %d published, %d held back until translated", len(out), held_back)
    return {"updated": int(time.time()), "stories": out, "held_back": held_back}


def main():
    items = asyncio.run(fetch_all())
    log.info("fetched %d articles", len(items))
    translator = Translator(CACHE)
    data = build(items, translator)
    translator.save()
    # Shown as a note on the GitHub Actions run page.
    print(f"::notice::{len(data['stories'])} stories, {data['held_back']} waiting for translation, "
          f"translations {translator.stats}")
    OUT.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    log.info("wrote %d stories to %s", len(data["stories"]), OUT)


if __name__ == "__main__":
    main()
