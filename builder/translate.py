"""Free translation to Arabic (Google Translate's public endpoint, MyMemory as backup) with an on-disk cache,
so each headline is only translated once."""
import hashlib
import json
import logging
import time
from pathlib import Path

import httpx

log = logging.getLogger(__name__)

CACHE_DAYS = 4
MAX_CHARS_PER_REQUEST = 4000
SEP = "\n"


class Translator:
    def __init__(self, cache_path: Path, client: httpx.Client | None = None):
        self.cache_path = cache_path
        self.client = client or httpx.Client(timeout=20, headers={"User-Agent": "Mozilla/5.0"})
        try:
            self.cache: dict[str, list] = json.loads(cache_path.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            self.cache = {}
        self.google_ok = True

    @staticmethod
    def _key(text: str) -> str:
        return hashlib.sha1(text.encode()).hexdigest()[:20]

    def _google(self, text: str) -> str:
        r = self.client.get(
            "https://translate.googleapis.com/translate_a/single",
            params={"client": "gtx", "sl": "auto", "tl": "ar", "dt": "t", "q": text},
        )
        r.raise_for_status()
        return "".join(seg[0] for seg in r.json()[0] if seg and seg[0])

    def _mymemory(self, text: str) -> str:
        r = self.client.get("https://api.mymemory.translated.net/get",
                            params={"q": text[:500], "langpair": "en|ar"})
        r.raise_for_status()
        data = r.json()
        if data.get("responseStatus") != 200:
            raise RuntimeError(data.get("responseDetails"))
        return data["responseData"]["translatedText"]

    def _translate_one(self, text: str) -> str | None:
        if self.google_ok:
            try:
                return self._google(text)
            except Exception as exc:
                log.warning("google translate unavailable, switching to MyMemory: %s", exc)
                self.google_ok = False
        try:
            return self._mymemory(text)
        except Exception as exc:
            log.warning("mymemory failed: %s", exc)
            return None

    def _translate_chunk(self, texts: list[str]) -> list[str | None]:
        """Several lines in one request; falls back to one-by-one if the line count comes back different."""
        if self.google_ok and len(texts) > 1:
            try:
                parts = self._google(SEP.join(texts)).split(SEP)
                if len(parts) == len(texts):
                    return [p.strip() for p in parts]
            except Exception as exc:
                log.warning("google batch failed: %s", exc)
                self.google_ok = False
        out = []
        for t in texts:
            out.append(self._translate_one(t))
            time.sleep(0.3)
        return out

    def translate_many(self, texts: list[str]) -> dict[str, str]:
        """Returns {original: arabic} for every text it could translate (cached or new)."""
        result, todo, cached = {}, [], 0
        now = int(time.time())
        for t in dict.fromkeys(t.replace(SEP, " ").strip() for t in texts if t and t.strip()):
            hit = self.cache.get(self._key(t))
            if hit:
                hit[1] = now
                result[t] = hit[0]
                cached += 1
            else:
                todo.append(t)

        chunk: list[str] = []
        for t in todo + [None]:  # None flushes the last chunk
            if t is not None and sum(len(c) + 1 for c in chunk) + len(t) < MAX_CHARS_PER_REQUEST:
                chunk.append(t)
                continue
            if chunk:
                for src, ar in zip(chunk, self._translate_chunk(chunk)):
                    if ar:
                        result[src] = ar
                        self.cache[self._key(src)] = [ar, now]
                time.sleep(0.5)
            chunk = [t] if t is not None else []
        log.info("translations: %d cached, %d new requested", cached, len(todo))
        return result

    def save(self):
        cutoff = time.time() - CACHE_DAYS * 86400
        self.cache = {k: v for k, v in self.cache.items() if v[1] >= cutoff}
        self.cache_path.write_text(json.dumps(self.cache, ensure_ascii=False), encoding="utf-8")
