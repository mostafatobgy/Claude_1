"""Free translation to Arabic with an on-disk cache, so each text is only translated once.

Tries two public Google endpoints, then MyMemory. A temporary block (HTTP 429) only pauses that
endpoint briefly instead of abandoning it for the whole run."""
import hashlib
import json
import logging
import time
from pathlib import Path

import httpx

log = logging.getLogger(__name__)

CACHE_DAYS = 4
MAX_CHARS_PER_REQUEST = 1800
SEP = "\n"
TIME_BUDGET_SECONDS = 8 * 60  # stop translating after this; the rest is retried next run


class Translator:
    def __init__(self, cache_path: Path, client: httpx.Client | None = None):
        self.cache_path = cache_path
        self.client = client or httpx.Client(timeout=20, headers={
            "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36"})
        try:
            self.cache: dict[str, list] = json.loads(cache_path.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            self.cache = {}
        self.failures = {"gtx": 0, "dict": 0, "mymemory": 0}
        self.stats = {"cached": 0, "new": 0, "failed": 0}
        self.started = time.time()

    @staticmethod
    def _key(text: str) -> str:
        return hashlib.sha1(text.encode()).hexdigest()[:20]

    def _gtx(self, text: str) -> str:
        r = self.client.post(
            "https://translate.googleapis.com/translate_a/single",
            params={"client": "gtx", "sl": "auto", "tl": "ar", "dt": "t"},
            data={"q": text},
        )
        r.raise_for_status()
        return "".join(seg[0] for seg in r.json()[0] if seg and seg[0])

    def _dict(self, text: str) -> str:
        r = self.client.post(
            "https://clients5.google.com/translate_a/t",
            params={"client": "dict-chrome-ex", "sl": "auto", "tl": "ar"},
            data={"q": text},
        )
        r.raise_for_status()
        data = r.json()
        if isinstance(data, dict):  # {"sentences": [{"trans": ...}, ...]}
            return "".join(s.get("trans", "") for s in data.get("sentences", []))
        first = data[0]  # [[translation, detected_lang]] or [translation]
        return first[0] if isinstance(first, list) else first

    def _mymemory(self, text: str) -> str:
        r = self.client.get("https://api.mymemory.translated.net/get",
                            params={"q": text[:500], "langpair": "en|ar", "de": "akhbar.app@example.com"})
        r.raise_for_status()
        data = r.json()
        if data.get("responseStatus") != 200:
            raise RuntimeError(data.get("responseDetails"))
        return data["responseData"]["translatedText"]

    def _try(self, text: str) -> str | None:
        """Each backend gets up to 3 consecutive failures before it is skipped for the rest of the run."""
        for name, fn in (("gtx", self._gtx), ("dict", self._dict), ("mymemory", self._mymemory)):
            if self.failures[name] >= 3 or (name == "mymemory" and SEP in text):
                continue
            for attempt in range(2):
                try:
                    out = fn(text).strip()
                    if out:
                        self.failures[name] = 0
                        return out
                except httpx.HTTPStatusError as exc:
                    log.warning("%s HTTP %s", name, exc.response.status_code)
                    if exc.response.status_code == 429 and attempt == 0:
                        time.sleep(8)
                        continue
                except Exception as exc:
                    log.warning("%s failed: %s", name, exc)
                break
            self.failures[name] += 1
        return None

    def _translate_chunk(self, texts: list[str]) -> list[str | None]:
        """Several lines in one request; falls back to one-by-one if the line count comes back different."""
        if len(texts) > 1:
            joined = self._try(SEP.join(texts))
            if joined:
                parts = [p.strip() for p in joined.split(SEP)]
                if len(parts) == len(texts):
                    return parts
        out = []
        for t in texts:
            out.append(self._try(t))
            time.sleep(0.4)
        return out

    def translate_many(self, texts: list[str]) -> dict[str, str]:
        """Returns {original: arabic} for every text it could translate (cached or new), in the given order of priority."""
        result, todo = {}, []
        now = int(time.time())
        for t in dict.fromkeys(t.replace(SEP, " ").strip() for t in texts if t and t.strip()):
            hit = self.cache.get(self._key(t))
            if hit:
                hit[1] = now
                result[t] = hit[0]
                self.stats["cached"] += 1
            else:
                todo.append(t)

        chunk: list[str] = []
        for t in todo + [None]:  # None flushes the last chunk
            if t is not None and sum(len(c) + 1 for c in chunk) + len(t) < MAX_CHARS_PER_REQUEST:
                chunk.append(t)
                continue
            if chunk:
                if time.time() - self.started > TIME_BUDGET_SECONDS:
                    self.stats["failed"] += len(chunk)
                else:
                    for src, ar in zip(chunk, self._translate_chunk(chunk)):
                        if ar:
                            result[src] = ar
                            self.cache[self._key(src)] = [ar, now]
                            self.stats["new"] += 1
                        else:
                            self.stats["failed"] += 1
                    time.sleep(1)
            chunk = [t] if t is not None else []
        log.info("translations: %s", self.stats)
        return result

    def save(self):
        cutoff = time.time() - CACHE_DAYS * 86400
        self.cache = {k: v for k, v in self.cache.items() if v[1] >= cutoff}
        self.cache_path.write_text(json.dumps(self.cache, ensure_ascii=False), encoding="utf-8")
