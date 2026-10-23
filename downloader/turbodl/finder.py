"""Find the direct video links behind a web page.

Three ways, tried in order:

1. The link already points at a video file or stream playlist.
2. yt-dlp, if installed (`pip install yt-dlp`): knows how to read 1,800+ sites
   such as YouTube, Vimeo, Facebook, X/Twitter, TikTok and Dailymotion. For
   YouTube it also needs Deno (see js_runtimes()).
3. A built-in page scan: reads the page's HTML and scripts for <video>/<source>
   tags, og:video tags, JSON-LD metadata and any .mp4/.webm/.m3u8/.mpd address.

Videos protected with DRM (Netflix, Disney+, paid Shahid, etc.) are encrypted;
their links are not usable and this module does not try to get around that.
"""

from __future__ import annotations

import concurrent.futures
import html
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from html.parser import HTMLParser

from .engine import USER_AGENT, safe_filename

VIDEO_EXTS = ("mp4", "m4v", "webm", "mkv", "mov", "avi", "flv", "wmv", "3gp", "ogv", "ts")
AUDIO_EXTS = ("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")
STREAM_EXTS = ("m3u8", "mpd")
STREAM_TYPES = {
    "application/vnd.apple.mpegurl": "m3u8",
    "application/x-mpegurl": "m3u8",
    "audio/mpegurl": "m3u8",
    "audio/x-mpegurl": "m3u8",
    "application/dash+xml": "mpd",
}
MAX_PAGE_BYTES = 8 * 1024 * 1024
MAX_CHECKS = 25  # how many found links to probe for size/type
# YouTube "player clients" (app profiles) to try when the default ones give no
# video with sound. YouTube sometimes marks every video as encrypted for one
# profile (see yt-dlp issue #12563) while others get the normal files.
YOUTUBE_FALLBACK_CLIENTS = ["android_vr", "web_safari", "mweb", "web_embedded", "tv_simply"]
DRM_MESSAGE = "This video is protected with DRM (encrypted), so it cannot be downloaded."
YOUTUBE_DRM_MESSAGE = (
    "YouTube only offered this video in encrypted form. Movies and shows bought or rented on YouTube "
    "are truly encrypted and cannot be downloaded. For a normal video this is usually temporary on "
    "YouTube's side: try again later, and use the newest TurboDL (it is rebuilt every week).")

# A media address inside HTML, JavaScript or JSON (after "\/" escapes are undone).
_EXT_ALT = "|".join(e for e in VIDEO_EXTS + STREAM_EXTS if e != "ts")
_URL_IN_TEXT = re.compile(
    r"""(?:https?:)?//[^\s"'<>()\\]+?\.(?:%s)(?:\?[^\s"'<>()\\]*)?(?=[\s"'<>()\\,;]|$)""" % _EXT_ALT, re.I)
# A site-relative address in quotes, e.g. "/media/clip.mp4".
_PATH_IN_TEXT = re.compile(r"""["'](/[^"'\s<>]+?\.(?:%s)(?:\?[^"'\s<>]*)?)["']""" % _EXT_ALT, re.I)
_HEIGHT = re.compile(r"(?<![0-9])(2160|1440|1080|720|540|480|360|240|144)[pP](?![a-z0-9])")


@dataclass
class VideoLink:
    url: str
    ext: str = ""  # mp4, webm, m3u8...
    kind: str = "video"  # "video" (with sound), "video only", "audio only", "stream"
    height: int | None = None
    size: int | None = None
    note: str = ""
    source: str = "page"  # "direct", "yt-dlp" or "page"
    headers: dict[str, str] = field(default_factory=dict)  # needed when downloading

    @property
    def quality(self) -> str:
        return f"{self.height}p" if self.height else ""

    @property
    def downloadable(self) -> bool:
        """A single file TurboDL can fetch (streams are playlists of many small pieces)."""
        return self.kind != "stream" and self.ext not in STREAM_EXTS

    @property
    def with_sound(self) -> bool:
        """One complete video file that has both picture and sound."""
        return self.kind == "video" and self.downloadable


@dataclass
class FindResult:
    page_url: str
    title: str = ""
    links: list[VideoLink] = field(default_factory=list)
    message: str = ""  # explanation when nothing usable was found

    def best(self) -> VideoLink | None:
        return next((v for v in self.links if v.with_sound), None) \
            or next((v for v in self.links if v.downloadable), None) \
            or (self.links[0] if self.links else None)

    def filename_for(self, link: VideoLink) -> str | None:
        if not self.title:
            return None
        name = self.title + (f" {link.quality}" if link.quality else "")
        return safe_filename(name + "." + (link.ext or "mp4")) or None


# ----------------------------------------------------------------- public ---

def find_videos(url: str, use_ytdlp: bool = True, timeout: float = 20,
                with_sound_only: bool = True) -> FindResult:
    """Find the video links behind `url`.

    By default only complete video files with sound are returned; picture-only,
    sound-only and stream (m3u8/mpd) links are left out.
    """
    url = url.strip()
    if not re.match(r"https?://", url, re.I):
        url = "https://" + url
    result = FindResult(page_url=url)

    try:
        resp = _open(url, timeout)
    except urllib.error.HTTPError as exc:
        resp = None
        result.message = f"The page answered HTTP {exc.code} {exc.reason}."
    except (urllib.error.URLError, OSError) as exc:
        resp = None
        reason = getattr(exc, "reason", exc)
        result.message = f"Could not open the page: {reason}"

    if resp is not None:
        with resp:
            final_url = resp.geturl()
            ctype = resp.headers.get_content_type()
            direct = _direct_link(final_url, ctype, resp.headers)
            if direct:
                result.links = [direct]
                result.title = _title_from_url(final_url)
                return _filtered(result, with_sound_only)
            body = resp.read(MAX_PAGE_BYTES) if ctype in ("text/html", "application/xhtml+xml", "text/plain") else b""
    else:
        final_url, body = url, b""

    ytdlp_message = ""
    if use_ytdlp:
        found = _with_ytdlp(url)
        if found and found.links:
            found = _filtered(found, with_sound_only)
            if found.links or not body or _is_youtube(url):
                return found
            # Only picture-only/sound-only/stream links: the page itself may still
            # hold a complete file, so scan it before giving up.
        ytdlp_message = found.message if found else ""
        if found and not found.links and _is_youtube(url):
            # YouTube pages hold no plain video links, so a page scan would only
            # replace yt-dlp's specific reason with a generic one.
            return found
        if ytdlp_message and not body:
            result.message = ytdlp_message

    if body:
        page = body.decode(_charset(resp), "replace")
        result.title, links = scan_page(page, final_url)
        result.links = _check_links(links, final_url, timeout)
        had_links = bool(result.links)
        result = _filtered(result, with_sound_only)
        if not result.links and not had_links:
            result.message = ytdlp_message or _nothing_found_message(use_ytdlp)
        elif not result.links and ytdlp_message:
            result.message = ytdlp_message
    return result


def _filtered(result: FindResult, with_sound_only: bool) -> FindResult:
    """Keep only complete videos with sound, explaining why if none are left."""
    if not with_sound_only:
        return result
    dropped = [v for v in result.links if not v.with_sound]
    result.links = [v for v in result.links if v.with_sound]
    if dropped and not result.links:
        kinds = {v.kind for v in dropped}
        if "video only" in kinds:
            result.message = ("This site keeps the picture and the sound in separate files, so there is "
                              "no single video with sound to download.")
        elif "stream" in kinds:
            result.message = ("This video is only offered as a stream (many small pieces), "
                              "not as one video file with sound.")
        else:
            result.message = "Only sound was found here (no picture), so it was skipped."
    return result


def ytdlp_available() -> bool:
    try:
        import yt_dlp  # noqa: F401
    except ImportError:
        return False
    return True


# ------------------------------------------------------------ page scanning ---

class _PageParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.title = ""
        self.og_title = ""
        self.candidates: list[tuple[str, str]] = []  # (url, hint) hint = type or label
        self.json_ld: list[str] = []
        self._in_title = False
        self._in_ld = False
        self._ld_buf: list[str] = []

    def handle_starttag(self, tag, attrs):
        a = {k.lower(): (v or "") for k, v in attrs}
        if tag == "title":
            self._in_title = True
        elif tag in ("video", "source", "audio"):
            for key in ("src", "data-src", "data-video-src"):
                if a.get(key):
                    self.candidates.append((a[key], " ".join(filter(None, (a.get("type"), a.get("label"),
                                                                              a.get("res"), a.get("size"))))))
        elif tag == "meta":
            prop = (a.get("property") or a.get("name") or "").lower()
            if prop in ("og:video", "og:video:url", "og:video:secure_url", "twitter:player:stream"):
                if a.get("content"):
                    self.candidates.append((a["content"], ""))
            elif prop == "og:title":
                self.og_title = a.get("content", "")
        elif tag == "a" and a.get("href"):
            if _ext_of(a["href"]) in VIDEO_EXTS + STREAM_EXTS:
                self.candidates.append((a["href"], ""))
        elif tag == "script" and "ld+json" in a.get("type", "").lower():
            self._in_ld = True
            self._ld_buf = []

    def handle_endtag(self, tag):
        if tag == "title":
            self._in_title = False
        elif tag == "script" and self._in_ld:
            self._in_ld = False
            self.json_ld.append("".join(self._ld_buf))

    def handle_data(self, data):
        if self._in_title:
            self.title += data
        if self._in_ld:
            self._ld_buf.append(data)


def scan_page(page: str, base_url: str) -> tuple[str, list[VideoLink]]:
    """Return the page title and every video address found in it (unchecked)."""
    parser = _PageParser()
    try:
        parser.feed(page)
        parser.close()
    except Exception:  # noqa: BLE001 - broken HTML still gets the regex scan below
        pass

    candidates = list(parser.candidates)
    for block in parser.json_ld:
        try:
            _collect_json_ld(json.loads(block), candidates)
        except ValueError:
            pass
    # Addresses inside scripts and JSON: undo "\/" and "&" escaping first.
    text = page.replace("\\/", "/").replace("\\u0026", "&").replace("\\u002F", "/").replace("&amp;", "&")
    for m in _URL_IN_TEXT.finditer(text):
        candidates.append((m.group(0), ""))
    for m in _PATH_IN_TEXT.finditer(text):
        candidates.append((m.group(1), ""))

    links: list[VideoLink] = []
    seen: set[str] = set()
    for raw, hint in candidates:
        url = _absolute(html.unescape(raw.strip()), base_url)
        if not url or url in seen:
            continue
        seen.add(url)
        ext = _ext_of(url)
        hint_l = hint.lower()
        if ext in STREAM_EXTS or "mpegurl" in hint_l or "dash" in hint_l:
            kind = "stream"
            ext = ext or ("mpd" if "dash" in hint_l else "m3u8")
        elif ext in AUDIO_EXTS or hint_l.startswith("audio/"):
            kind = "audio only"
        elif ext == "ts" and "/seg" in url.lower():
            continue  # one piece of a stream, not a whole video
        else:
            kind = "video"
        m = _HEIGHT.search(hint) or _HEIGHT.search(urllib.parse.unquote(url))
        links.append(VideoLink(url=url, ext=ext, kind=kind, height=int(m.group(1)) if m else None))

    title = html.unescape((parser.og_title or parser.title).strip())
    return re.sub(r"\s+", " ", title), links


def _collect_json_ld(node, out: list[tuple[str, str]]) -> None:
    if isinstance(node, list):
        for item in node:
            _collect_json_ld(item, out)
    elif isinstance(node, dict):
        if isinstance(node.get("contentUrl"), str):
            out.append((node["contentUrl"], node.get("encodingFormat", "") or ""))
        for key in ("@graph", "video", "associatedMedia", "hasPart"):
            if key in node:
                _collect_json_ld(node[key], out)


# ------------------------------------------------------------------ yt-dlp ---

def _with_ytdlp(url: str) -> FindResult | None:
    try:
        import yt_dlp  # noqa: F401
    except ImportError:
        return None
    result = _ytdlp_extract(url)
    if _is_youtube(url) and not any(v.with_sound for v in result.links):
        retry = _ytdlp_extract(url, {"youtube": {"player_client": YOUTUBE_FALLBACK_CLIENTS}})
        if any(v.with_sound for v in retry.links) or (retry.links and not result.links):
            result = retry
    if not result.links and "DRM" in result.message and _is_youtube(url):
        result.message = YOUTUBE_DRM_MESSAGE
    return result


def _ytdlp_extract(url: str, extractor_args: dict | None = None) -> FindResult:
    import yt_dlp

    opts = {"quiet": True, "no_warnings": True, "skip_download": True, "noplaylist": False,
            "extract_flat": False, "playlistend": 20, "logger": _QuietLogger(),
            "js_runtimes": js_runtimes()}
    if extractor_args:
        opts["extractor_args"] = extractor_args
    try:
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
            info = ydl.sanitize_info(info)
    except Exception as exc:  # noqa: BLE001 - yt-dlp raises many kinds
        msg = re.sub(r"^ERROR:\s*", "", str(exc)).strip()
        if "Unsupported URL" in msg:
            msg = ""  # not a site yt-dlp knows; the page scan explains better
        elif "drm" in msg.lower():
            msg = DRM_MESSAGE
        return FindResult(page_url=url, message=msg)
    result = from_ytdlp_info(info, url)
    if (info.get("extractor_key") or "").lower() in ("generic", "html5mediaembed"):
        # yt-dlp's catch-all page reader does not test what it finds; drop dead links.
        result.links = _check_links(result.links, url, 20)
    return result


def js_runtimes() -> dict:
    """Tell yt-dlp where Deno is. With it, yt-dlp can use all of YouTube's app profiles.

    The Windows app ships deno.exe in the same folder as TurboDL.exe; otherwise
    yt-dlp looks for Deno on the PATH.
    """
    folders = [os.path.dirname(sys.executable)] if getattr(sys, "frozen", False) else []
    folders.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))  # source checkout
    for folder in folders:
        for name in ("deno.exe", "deno"):
            path = os.path.join(folder, name)
            if os.path.isfile(path):
                return {"deno": {"path": path}}
    return {"deno": {}}


def _is_youtube(url: str) -> bool:
    host = (urllib.parse.urlparse(url).hostname or "").lower()
    return host == "youtu.be" or host == "youtube.com" or host.endswith(".youtube.com")


class _QuietLogger:
    def debug(self, msg):
        pass

    def warning(self, msg):
        pass

    def error(self, msg):
        pass


def from_ytdlp_info(info: dict, page_url: str) -> FindResult:
    """Turn yt-dlp's description of a video (or playlist) into a FindResult."""
    result = FindResult(page_url=page_url, title=info.get("title") or "")
    entries = info.get("entries")
    videos = [e for e in entries if e] if entries else [info]
    multi = len(videos) > 1
    for video in videos:
        formats = video.get("formats") or ([video] if video.get("url") else [])
        prefix = (video.get("title") or "") if multi else ""
        for f in formats:
            link = _from_ytdlp_format(f, video, prefix)
            if link and all(link.url != v.url for v in result.links):
                result.links.append(link)
    # yt-dlp's "_has_drm" means *some* formats were encrypted and left out, so
    # only call the video encrypted when nothing usable is left.
    if not result.links and videos and all(v.get("_has_drm") or v.get("has_drm") for v in videos):
        result.message = DRM_MESSAGE
    result.links.sort(key=_rank)
    return result


def _from_ytdlp_format(f: dict, video: dict, prefix: str) -> VideoLink | None:
    url = f.get("url")
    if not url or f.get("has_drm") or f.get("ext") == "mhtml" or "storyboard" in (f.get("format_note") or ""):
        return None
    vcodec, acodec = f.get("vcodec"), f.get("acodec")
    protocol = f.get("protocol") or ""
    if "m3u8" in protocol or "dash" in protocol or f.get("ext") in STREAM_EXTS:
        kind = "stream"
    elif vcodec == "none" and acodec not in (None, "none"):
        kind = "audio only"
    elif acodec == "none" and vcodec not in (None, "none"):
        kind = "video only"
    else:
        kind = "video"
    note = " · ".join(filter(None, (prefix, f.get("format_note"), _codec(vcodec), _codec(acodec))))
    ext = f.get("ext") or ""
    if kind == "stream":
        ext = "mpd" if "dash" in protocol else "m3u8"
    return VideoLink(
        url=url,
        ext=ext,
        kind=kind,
        height=f.get("height"),
        size=f.get("filesize") or f.get("filesize_approx"),
        note=note,
        source="yt-dlp",
        headers={k: v for k, v in (f.get("http_headers") or video.get("http_headers") or {}).items()},
    )


def _codec(c) -> str:
    return "" if c in (None, "none") else str(c).split(".")[0]


# ----------------------------------------------------------------- helpers ---

_KIND_ORDER = {"video": 0, "video only": 1, "stream": 2, "audio only": 3}


def _rank(v: VideoLink):
    return (_KIND_ORDER.get(v.kind, 9), -(v.height or 0), -(v.size or 0))


def _open(url: str, timeout: float, method: str = "GET", headers: dict | None = None):
    req = urllib.request.Request(url, method=method, headers={
        "User-Agent": f"Mozilla/5.0 (compatible; {USER_AGENT})",
        "Accept": "text/html,application/xhtml+xml,video/*,*/*;q=0.8",
        **(headers or {}),
    })
    return urllib.request.urlopen(req, timeout=timeout)


def _charset(resp) -> str:
    return (resp.headers.get_content_charset() if resp is not None else None) or "utf-8"


def _ext_of(url: str) -> str:
    path = urllib.parse.urlparse(url).path
    m = re.search(r"\.([A-Za-z0-9]{2,4})$", path)
    return m.group(1).lower() if m else ""


def _absolute(url: str, base: str) -> str | None:
    if url.startswith(("blob:", "data:", "javascript:")):
        return None  # blob: only exists inside the browser tab
    if url.startswith("//"):
        url = urllib.parse.urlparse(base).scheme + ":" + url
    url = urllib.parse.urljoin(base, url)
    return url if url.startswith(("http://", "https://")) else None


def _title_from_url(url: str) -> str:
    name = urllib.parse.unquote(urllib.parse.urlparse(url).path.rsplit("/", 1)[-1])
    return name.rsplit(".", 1)[0]


def _direct_link(url: str, ctype: str, headers) -> VideoLink | None:
    ext = _ext_of(url)
    size = None
    m = re.match(r"bytes\s+\d+-\d+/(\d+)", headers.get("Content-Range", ""))
    if m:
        size = int(m.group(1))
    elif headers.get("Content-Length", "").isdigit():
        size = int(headers["Content-Length"])
    hm = _HEIGHT.search(urllib.parse.unquote(url))
    height = int(hm.group(1)) if hm else None
    if ctype in STREAM_TYPES:
        return VideoLink(url, STREAM_TYPES[ctype], "stream", height, None, source="direct")
    if ctype.startswith("video/") or (ctype in ("application/octet-stream", "binary/octet-stream")
                                      and ext in VIDEO_EXTS):
        return VideoLink(url, ext or ctype.split("/")[-1], "video", height, size, source="direct")
    if ctype.startswith("audio/"):
        return VideoLink(url, ext or ctype.split("/")[-1], "audio only", height, size, source="direct")
    return None


def _check_links(links: list[VideoLink], referer: str, timeout: float) -> list[VideoLink]:
    """Ask the server about each found link: keep real media, fill in size and type."""
    links = links[:MAX_CHECKS]

    def check(link: VideoLink) -> VideoLink | None:
        headers = {"Referer": referer, "Range": "bytes=0-0"}
        try:
            with _open(link.url, min(timeout, 10), headers=headers) as resp:
                ctype = resp.headers.get_content_type()
                found = _direct_link(resp.geturl(), ctype, resp.headers)
        except urllib.error.HTTPError as exc:
            # Some servers refuse robots but serve browsers; keep links that look right.
            return link if exc.code in (401, 403) else None
        except (urllib.error.URLError, OSError):
            return link  # could not check (e.g. timeout) - still worth showing
        if not found:
            keep = link.kind == "stream" or ctype in ("application/octet-stream", "binary/octet-stream")
            return link if keep else None
        link.size = found.size if found.kind != "stream" else None
        if found.kind == "stream":
            link.kind, link.ext = "stream", found.ext
        elif found.kind == "audio only":
            link.kind = "audio only"
        link.headers = {"Referer": referer}
        return link

    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        checked = [v for v in pool.map(check, links) if v]
    checked.sort(key=_rank)
    return checked


def _nothing_found_message(use_ytdlp: bool) -> str:
    # Pages are not judged "encrypted" from their text: words like "widevine" also
    # appear in players (YouTube's included) that play normal, unencrypted video.
    hint = "" if not use_ytdlp or ytdlp_available() else (
        " Installing yt-dlp (pip install yt-dlp) adds support for YouTube, Facebook, X, TikTok and many more sites.")
    return ("No video address was found in this page. The video may be created by scripts after the page "
            "loads - the TurboDL browser extension can catch those while the video plays." + hint)
