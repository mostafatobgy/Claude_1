"""Multi-connection download engine.

How it speeds things up: many servers and CDNs cap the speed of each single
connection. The engine asks the server for different byte ranges of the same
file over several connections at once (HTTP Range requests) and writes each
piece into its place in the output file. When a connection finishes its piece
early, it takes over half of the biggest piece still left ("work stealing"),
so every connection stays busy until the very end.

Progress is saved next to the file (<name>.part + <name>.part.turbodl), so a
paused, crashed or interrupted download continues where it stopped.

Only the Python standard library is used.
"""

from __future__ import annotations

import email.message
import hashlib
import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Callable

DEFAULT_CONNECTIONS = 8
MAX_CONNECTIONS = 32
CHUNK_SIZE = 64 * 1024
# A piece is only split when both halves would be at least this big; smaller
# pieces finish faster on their current connection than on a new one.
MIN_SPLIT = 1024 * 1024
USER_AGENT = "TurboDL/1.0 (+https://github.com/mostafatobgy/claude_1)"
STATE_SUFFIX = ".turbodl"
PART_SUFFIX = ".part"


class DownloadError(Exception):
    pass


class Cancelled(Exception):
    pass


@dataclass
class RemoteInfo:
    url: str  # final URL after redirects
    size: int | None
    resumable: bool  # server honours Range requests
    filename: str
    etag: str | None = None
    last_modified: str | None = None


@dataclass
class Segment:
    start: int
    end: int  # inclusive; may shrink when another connection steals the tail
    pos: int  # next byte to hand out (reserved before it is written)
    written: int  # bytes [start, written) are safely on disk
    busy: bool = False

    @property
    def remaining(self) -> int:
        return self.end - self.pos + 1


@dataclass
class Stats:
    total: int | None
    downloaded: int
    speed: float  # bytes per second, smoothed
    connections: int
    state: str  # queued, connecting, downloading, paused, done, error, cancelled
    error: str | None = None

    @property
    def fraction(self) -> float | None:
        if not self.total:
            return None
        return min(1.0, self.downloaded / self.total)

    @property
    def eta(self) -> float | None:
        if not self.total or self.speed <= 0:
            return None
        return max(0.0, (self.total - self.downloaded) / self.speed)


class RateLimiter:
    """Token bucket shared by all connections (bytes per second; 0 = unlimited)."""

    def __init__(self, rate: int = 0):
        self.rate = rate
        self._tokens = 0.0
        self._last = time.monotonic()
        self._lock = threading.Lock()

    def consume(self, n: int, stop: threading.Event) -> None:
        if self.rate <= 0:
            return
        while not stop.is_set():
            with self._lock:
                now = time.monotonic()
                self._tokens = min(self.rate, self._tokens + (now - self._last) * self.rate)
                self._last = now
                if self._tokens >= n or self._tokens >= self.rate:
                    self._tokens -= n
                    return
                wait = (n - self._tokens) / self.rate
            stop.wait(min(wait, 0.25))


# ---------------------------------------------------------------- helpers ---

def _filename_from_headers(headers: email.message.Message, url: str) -> str:
    cd = headers.get("Content-Disposition", "")
    m = re.search(r"filename\*\s*=\s*([^']*)'[^']*'([^;]+)", cd, re.I)
    if m:
        name = urllib.parse.unquote(m.group(2).strip().strip('"'), encoding=m.group(1) or "utf-8")
    else:
        m = re.search(r'filename\s*=\s*"([^"]+)"', cd, re.I) or re.search(r"filename\s*=\s*([^;]+)", cd, re.I)
        name = m.group(1).strip() if m else ""
    if not name:
        name = urllib.parse.unquote(os.path.basename(urllib.parse.urlparse(url).path))
    return safe_filename(name) or "download"


def safe_filename(name: str) -> str:
    name = os.path.basename(name.replace("\\", "/"))
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")
    return name[:200]


def unique_path(path: str) -> str:
    """Return `path`, or `name (1).ext`, `name (2).ext`... if it is taken."""
    if not os.path.exists(path) and not os.path.exists(path + PART_SUFFIX):
        return path
    root, ext = os.path.splitext(path)
    i = 1
    while True:
        candidate = f"{root} ({i}){ext}"
        if not os.path.exists(candidate) and not os.path.exists(candidate + PART_SUFFIX):
            return candidate
        i += 1


def human_size(n: float | None) -> str:
    if n is None:
        return "?"
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if abs(n) < 1024 or unit == "TB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} TB"


def human_time(seconds: float | None) -> str:
    if seconds is None:
        return "--:--"
    seconds = int(seconds)
    h, rest = divmod(seconds, 3600)
    m, s = divmod(rest, 60)
    return f"{h}:{m:02d}:{s:02d}" if h else f"{m:02d}:{s:02d}"


def parse_rate(text: str) -> int:
    """'500K', '2M', '1.5MB' -> bytes per second. '' or '0' -> 0 (unlimited)."""
    text = text.strip().upper().removesuffix("/S").removesuffix("B")
    if not text:
        return 0
    mult = {"K": 1024, "M": 1024**2, "G": 1024**3}.get(text[-1], 1)
    if text[-1] in "KMG":
        text = text[:-1]
    return int(float(text) * mult)


# --------------------------------------------------------------- download ---

class Download:
    """One file being downloaded. Thread-safe; call start() or run()."""

    def __init__(
        self,
        url: str,
        folder: str = ".",
        filename: str | None = None,
        connections: int = DEFAULT_CONNECTIONS,
        headers: dict[str, str] | None = None,
        rate_limit: int = 0,
        timeout: float = 30,
        max_retries: int = 8,
        sha256: str | None = None,
    ):
        self.url = url
        self.folder = folder
        self.requested_name = filename
        self.connections = max(1, min(MAX_CONNECTIONS, connections))
        self.extra_headers = dict(headers or {})
        self.headers = {"User-Agent": USER_AGENT, **self.extra_headers}
        self.limiter = RateLimiter(rate_limit)
        self.timeout = timeout
        self.max_retries = max_retries
        self.sha256 = sha256.lower() if sha256 else None

        self.info: RemoteInfo | None = None
        self.path: str | None = None  # final file path
        self.segments: list[Segment] = []
        self.state = "queued"
        self.error: str | None = None

        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._cancel = False
        self._thread: threading.Thread | None = None
        self._active = 0
        self._failure: BaseException | None = None
        self._speed = 0.0
        self._speed_mark = (time.monotonic(), 0)
        self._stream_downloaded = 0  # for downloads without known size

    # ---------------------------------------------------------- public API

    def start(self) -> None:
        """Run in a background thread."""
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._cancel = False
        self._thread = threading.Thread(target=self._run_safe, daemon=True)
        self._thread.start()

    def run(self) -> None:
        """Run in the calling thread; raises on failure."""
        self._stop.clear()
        self._cancel = False
        self._run()

    def pause(self) -> None:
        self._stop.set()

    def cancel(self) -> None:
        """Stop and delete the partial file."""
        self._cancel = True
        self._stop.set()
        if not (self._thread and self._thread.is_alive()):
            self._cleanup_partial()
            self.state = "cancelled"

    def wait(self, timeout: float | None = None) -> None:
        if self._thread:
            self._thread.join(timeout)

    @property
    def name(self) -> str:
        if self.path:
            return os.path.basename(self.path)
        return self.requested_name or safe_filename(urllib.parse.unquote(
            os.path.basename(urllib.parse.urlparse(self.url).path))) or self.url

    def stats(self) -> Stats:
        with self._lock:
            downloaded = self._downloaded_locked()
            now = time.monotonic()
            t0, b0 = self._speed_mark
            if now - t0 >= 0.5:
                inst = (downloaded - b0) / (now - t0)
                # Exponential smoothing keeps the number readable.
                self._speed = inst if self._speed == 0 else 0.7 * self._speed + 0.3 * inst
                self._speed_mark = (now, downloaded)
            speed = self._speed if self.state == "downloading" else 0.0
            return Stats(
                total=self.info.size if self.info else None,
                downloaded=downloaded,
                speed=max(0.0, speed),
                connections=self._active,
                state=self.state,
                error=self.error,
            )

    # ------------------------------------------------------------ internals

    def _downloaded_locked(self) -> int:
        if self.segments:
            return sum(s.written - s.start for s in self.segments)
        return self._stream_downloaded

    def _run_safe(self) -> None:
        try:
            self._run()
        except Exception:
            pass  # state/error already recorded

    def _run(self) -> None:
        self.error = None
        self._failure = None
        self.state = "connecting"
        try:
            if self.info is None:
                self.info = self.probe()
            self._prepare()
            self.state = "downloading"
            if self.info.resumable and self.info.size:
                self._run_segmented()
            else:
                self._run_single_stream()
            if self._stop.is_set():
                raise Cancelled()
            self._finish()
            self.state = "done"
        except Cancelled:
            if self._cancel:
                self._cleanup_partial()
                self.state = "cancelled"
            else:
                self._save_state()
                self.state = "paused"
        except Exception as exc:  # noqa: BLE001 - surfaced to the user
            self._save_state()
            self.state = "error"
            self.error = _describe(exc)
            raise

    def _open(self, extra_headers: dict[str, str] | None = None, url: str | None = None):
        req = urllib.request.Request(url or self.url, headers={**self.headers, **(extra_headers or {})})
        return urllib.request.urlopen(req, timeout=self.timeout)

    def probe(self) -> RemoteInfo:
        """Find size, file name and whether the server supports ranges."""
        last_exc: Exception | None = None
        for attempt in range(3):
            try:
                with self._open({"Range": "bytes=0-0"}) as resp:
                    final_url = resp.geturl()
                    headers = resp.headers
                    size = None
                    resumable = False
                    if resp.status == 206:
                        m = re.match(r"bytes\s+\d+-\d+/(\d+)", headers.get("Content-Range", ""))
                        if m:
                            size = int(m.group(1))
                            resumable = True
                    elif headers.get("Content-Length"):
                        size = int(headers["Content-Length"])
                    return RemoteInfo(
                        url=final_url,
                        size=size,
                        resumable=resumable,
                        filename=_filename_from_headers(headers, final_url),
                        etag=headers.get("ETag"),
                        last_modified=headers.get("Last-Modified"),
                    )
            except urllib.error.HTTPError as exc:
                if exc.code == 416:  # empty file: "range not satisfiable"
                    return RemoteInfo(self.url, 0, False, _filename_from_headers(exc.headers, self.url))
                if exc.code < 500 and exc.code != 429:
                    raise
                last_exc = exc
            except (urllib.error.URLError, OSError) as exc:
                last_exc = exc
            self._stop.wait(2**attempt)
            if self._stop.is_set():
                raise Cancelled()
        raise last_exc  # type: ignore[misc]

    def _prepare(self) -> None:
        assert self.info
        os.makedirs(self.folder, exist_ok=True)
        if self.path is None:
            name = safe_filename(self.requested_name) if self.requested_name else self.info.filename
            path = os.path.join(self.folder, name)
            if self._load_state(path):
                return
            self.path = unique_path(path)
        elif self.segments or self._load_state(self.path):
            return

        part = self.path + PART_SUFFIX
        size = self.info.size
        if self.info.resumable and size:
            n = self.connections
            step = max(MIN_SPLIT, -(-size // n))
            self.segments = []
            for start in range(0, size, step):
                end = min(size, start + step) - 1
                self.segments.append(Segment(start, end, start, start))
            with open(part, "wb") as f:
                f.truncate(size)  # reserve the space up front (sparse where supported)
            self._save_state()
        else:
            open(part, "wb").close()

    # -- resume state

    def _state_file(self, path: str) -> str:
        return path + PART_SUFFIX + STATE_SUFFIX

    def _save_state(self) -> None:
        if not (self.path and self.info and self.segments):
            return
        with self._lock:
            data = {
                "version": 1,
                "url": self.url,
                "size": self.info.size,
                "etag": self.info.etag,
                "last_modified": self.info.last_modified,
                "segments": [[s.start, s.end, s.written] for s in self.segments],
            }
        tmp = self._state_file(self.path) + ".tmp"
        try:
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(data, f)
            os.replace(tmp, self._state_file(self.path))
        except OSError:
            pass

    def _load_state(self, path: str) -> bool:
        """Pick up a previous unfinished download of the same file."""
        assert self.info
        state_file = self._state_file(path)
        if not (os.path.exists(state_file) and os.path.exists(path + PART_SUFFIX)):
            return False
        try:
            with open(state_file, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError):
            return False
        same = (
            self.info.resumable
            and data.get("size") == self.info.size
            and (not data.get("etag") or not self.info.etag or data["etag"] == self.info.etag)
            and (not data.get("last_modified") or not self.info.last_modified
                 or data["last_modified"] == self.info.last_modified)
        )
        if not same:
            return False  # the file on the server changed; start over under a new name
        self.path = path
        self.segments = [Segment(a, b, w, w) for a, b, w in data["segments"]]
        return True

    # -- segmented mode

    def _run_segmented(self) -> None:
        threads = []
        for _ in range(self.connections):
            t = threading.Thread(target=self._worker, daemon=True)
            t.start()
            threads.append(t)
        last_save = time.monotonic()
        while any(t.is_alive() for t in threads):
            for t in threads:
                t.join(0.2)
            if time.monotonic() - last_save > 2:
                self._save_state()
                last_save = time.monotonic()
        if self._failure:
            raise self._failure
        if not self._stop.is_set() and any(s.written <= s.end for s in self.segments):
            raise DownloadError("download stopped before all parts were received")

    def _claim(self) -> Segment | None:
        """Give an idle connection an unfinished piece, splitting one if needed."""
        with self._lock:
            for s in self.segments:
                if not s.busy and s.pos <= s.end:
                    s.busy = True
                    return s
            # Steal the back half of the largest piece someone else is working on.
            victim = max((s for s in self.segments if s.busy), key=lambda s: s.remaining, default=None)
            if victim is None or victim.remaining < 2 * MIN_SPLIT:
                return None
            mid = victim.pos + victim.remaining // 2
            new = Segment(mid, victim.end, mid, mid, busy=True)
            victim.end = mid - 1
            self.segments.append(new)
            self.segments.sort(key=lambda s: s.start)
            return new

    def _worker(self) -> None:
        assert self.path
        part = self.path + PART_SUFFIX
        with open(part, "r+b") as f:
            while not self._stop.is_set():
                seg = self._claim()
                if seg is None:
                    return
                with self._lock:
                    self._active += 1
                try:
                    self._fetch_segment(seg, f)
                except Cancelled:
                    return
                except Exception as exc:  # noqa: BLE001
                    with self._lock:
                        if self._failure is None:
                            self._failure = exc
                    self._stop.set()
                    return
                finally:
                    with self._lock:
                        self._active -= 1
                        seg.busy = False

    def _fetch_segment(self, seg: Segment, f) -> None:
        assert self.info
        failures = 0
        while True:
            with self._lock:
                if seg.pos > seg.end:
                    return
                start, end = seg.pos, seg.end
            try:
                with self._open({"Range": f"bytes={start}-{end}"}, url=self.info.url) as resp:
                    if resp.status != 206:
                        raise DownloadError("server stopped accepting partial requests")
                    m = re.match(r"bytes\s+(\d+)-", resp.headers.get("Content-Range", ""))
                    if m and int(m.group(1)) != start:
                        raise DownloadError("server returned the wrong part of the file")
                    while True:
                        if self._stop.is_set():
                            raise Cancelled()
                        data = resp.read(CHUNK_SIZE)
                        if not data:
                            break
                        with self._lock:
                            # The tail may have been handed to another connection meanwhile.
                            take = min(len(data), seg.end - seg.pos + 1)
                            offset = seg.pos
                            seg.pos += take
                        if take > 0:
                            self.limiter.consume(take, self._stop)
                            f.seek(offset)
                            f.write(data[:take])
                            with self._lock:
                                seg.written = offset + take
                            failures = 0
                        if take < len(data) or seg.pos > seg.end:
                            return
                with self._lock:
                    if seg.pos > seg.end:
                        return
                raise DownloadError("connection closed early")
            except Cancelled:
                raise
            except Exception as exc:  # noqa: BLE001
                if isinstance(exc, urllib.error.HTTPError) and exc.code < 500 and exc.code not in (408, 429):
                    raise
                failures += 1
                if failures > self.max_retries:
                    raise
                with self._lock:
                    seg.pos = seg.written  # re-request whatever was not saved
                self._stop.wait(min(30, 2 ** (failures - 1)))
                if self._stop.is_set():
                    raise Cancelled()

    # -- single-connection mode (server without Range support)

    def _run_single_stream(self) -> None:
        assert self.path and self.info
        part = self.path + PART_SUFFIX
        failures = 0
        while True:
            self._stream_downloaded = 0
            try:
                with self._open(url=self.info.url) as resp, open(part, "wb") as f:
                    with self._lock:
                        self._active = 1
                    while True:
                        if self._stop.is_set():
                            raise Cancelled()
                        data = resp.read(CHUNK_SIZE)
                        if not data:
                            break
                        self.limiter.consume(len(data), self._stop)
                        f.write(data)
                        with self._lock:
                            self._stream_downloaded += len(data)
                if self.info.size is not None and self._stream_downloaded < self.info.size:
                    raise DownloadError("connection closed early")
                if self.info.size is None:
                    self.info.size = self._stream_downloaded
                return
            except Cancelled:
                raise
            except Exception as exc:  # noqa: BLE001
                if isinstance(exc, urllib.error.HTTPError) and exc.code < 500 and exc.code not in (408, 429):
                    raise
                failures += 1
                if failures > self.max_retries:
                    raise
                self._stop.wait(min(30, 2 ** (failures - 1)))
                if self._stop.is_set():
                    raise Cancelled()
            finally:
                with self._lock:
                    self._active = 0

    # -- finishing

    def _finish(self) -> None:
        assert self.path
        part = self.path + PART_SUFFIX
        if self.sha256:
            self.state = "verifying"
            h = hashlib.sha256()
            with open(part, "rb") as f:
                for block in iter(lambda: f.read(1024 * 1024), b""):
                    h.update(block)
            if h.hexdigest() != self.sha256:
                raise DownloadError("SHA-256 checksum does not match - the file is damaged")
        os.replace(part, self.path)
        try:
            os.remove(self._state_file(self.path))
        except FileNotFoundError:
            pass

    def _cleanup_partial(self) -> None:
        if not self.path:
            return
        for p in (self.path + PART_SUFFIX, self._state_file(self.path)):
            try:
                os.remove(p)
            except FileNotFoundError:
                pass


def _describe(exc: BaseException) -> str:
    if isinstance(exc, urllib.error.HTTPError):
        return f"HTTP {exc.code} {exc.reason}"
    if isinstance(exc, urllib.error.URLError):
        return f"Network error: {exc.reason}"
    return str(exc) or exc.__class__.__name__


ProgressCallback = Callable[[Stats], None]
