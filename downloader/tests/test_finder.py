import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from turbodl import cli
from turbodl.finder import find_videos, from_ytdlp_info, scan_page, ytdlp_available

VIDEO = b"\x00\x00\x00\x18ftypmp42" + os.urandom(300_000)

PAGES = {
    # Plain HTML5 player with several qualities, like most small video sites.
    "/watch/1": """<html><head><title>Nile Sunset &amp; Boats</title>
        <meta property="og:video" content="/media/clip_720p.mp4"></head><body>
        <video controls poster="/p.jpg">
          <source src="/media/clip_720p.mp4" type="video/mp4" label="720p">
          <source src="clip_360p.webm" type="video/webm">
          <source src="/media/missing_1080p.mp4" type="video/mp4">
        </video></body></html>""",
    # Player configured from a script with escaped JSON, like many news and social sites.
    "/watch/2": """<html><head><title>Player</title><meta property="og:title" content="Match highlights">
        <script>window.__DATA__ = {"video":{"hd":"http:\\/\\/HOST\\/media\\/hd_1080p.mp4?sig=a\\u0026e=1",
        "hls":"/stream/master.m3u8"}, "app": "/static/app.js"};</script></head><body>
        <div id="player"></div></body></html>""",
    # Schema.org metadata only (what search engines read).
    "/watch/3": """<html><head><title>Lecture</title><script type="application/ld+json">
        {"@context":"https://schema.org","@type":"VideoObject","name":"Lecture",
         "contentUrl":"http://HOST/media/lecture.mp4"}</script></head><body></body></html>""",
    "/watch/streamonly": '<html><body><video src="/stream/master.m3u8"></video></body></html>',
    "/watch/none": "<html><head><title>Just text</title></head><body><p>hello</p></body></html>",
    "/watch/drm": "<html><body><script>initPlayer({drm:{widevine:{url:'/lic'}}})</script></body></html>",
}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_GET(self):
        host = self.headers["Host"]
        path = self.path.split("?")[0]
        if path in PAGES:
            body = PAGES[path].replace("HOST", host).encode()
            ctype = "text/html; charset=utf-8"
        elif path.startswith("/media/") and "missing" not in path:
            body = VIDEO
            ctype = {"webm": "video/webm", "mp3": "audio/mpeg"}.get(path.rsplit(".", 1)[-1], "video/mp4")
        elif path == "/watch/clip_360p.webm":
            body, ctype = VIDEO, "video/webm"
        elif path == "/stream/master.m3u8":
            body = b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow.m3u8\n"
            ctype = "application/vnd.apple.mpegurl"
        else:
            self.send_error(404)
            return
        start, end = 0, len(body) - 1
        rng = self.headers.get("Range")
        if rng and ctype.startswith("video"):
            a, b = rng.split("=")[1].split("-")
            start, end = int(a), min(int(b) if b else end, end)
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(body)}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        self.wfile.write(body[start:end + 1])


@pytest.fixture(scope="module")
def server():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()


def test_html5_player(server):
    r = find_videos(server + "/watch/1", use_ytdlp=False)
    assert r.title == "Nile Sunset & Boats"
    urls = [v.url for v in r.links]
    assert urls == [server + "/media/clip_720p.mp4", server + "/watch/clip_360p.webm"]  # 404 dropped
    best = r.best()
    assert best.quality == "720p" and best.size == len(VIDEO) and best.downloadable
    assert r.filename_for(best) == "Nile Sunset & Boats 720p.mp4"


def test_links_inside_scripts(server):
    r = find_videos(server + "/watch/2", use_ytdlp=False, with_sound_only=False)
    assert r.title == "Match highlights"
    kinds = {v.url.split("/")[-1]: v.kind for v in r.links}
    assert kinds == {"hd_1080p.mp4?sig=a&e=1": "video", "master.m3u8": "stream"}
    assert r.best().height == 1080
    assert not [v for v in r.links if v.kind == "stream"][0].downloadable


def test_only_videos_with_sound_by_default(server):
    r = find_videos(server + "/watch/2", use_ytdlp=False)
    assert [v.url.split("/")[-1] for v in r.links] == ["hd_1080p.mp4?sig=a&e=1"]

    r = find_videos(server + "/watch/streamonly", use_ytdlp=False)
    assert not r.links and "stream" in r.message

    r = find_videos(server + "/media/song.mp3", use_ytdlp=False)
    assert not r.links and "Only sound" in r.message
    r = find_videos(server + "/media/song.mp3", use_ytdlp=False, with_sound_only=False)
    assert [v.kind for v in r.links] == ["audio only"]


def test_json_ld(server):
    r = find_videos(server + "/watch/3", use_ytdlp=False)
    assert [v.url for v in r.links] == [server + "/media/lecture.mp4"]


def test_direct_video_link(server):
    r = find_videos(server + "/media/movie.mp4", use_ytdlp=False)
    assert len(r.links) == 1 and r.links[0].source == "direct" and r.links[0].size == len(VIDEO)


def test_nothing_found_and_drm_messages(server):
    r = find_videos(server + "/watch/none", use_ytdlp=False)
    assert not r.links and "browser extension" in r.message
    r = find_videos(server + "/watch/drm", use_ytdlp=False)
    assert not r.links and "DRM" in r.message


def test_unreachable_page():
    r = find_videos("http://127.0.0.1:1/x", use_ytdlp=False)
    assert not r.links and "Could not open" in r.message


def test_scan_ignores_blob_and_scripts():
    _, links = scan_page('<video src="blob:https://x/123"></video><script src="/a.js"></script>'
                         '<a href="/files/talk.mkv">mkv</a>', "https://site.example/page")
    assert [v.url for v in links] == ["https://site.example/files/talk.mkv"]


def test_ytdlp_info_mapping():
    # Shape of what yt-dlp reports for a YouTube-style video.
    info = {
        "title": "Some talk",
        "http_headers": {"User-Agent": "UA"},
        "formats": [
            {"format_id": "sb0", "ext": "mhtml", "url": "https://i/sb", "format_note": "storyboard"},
            {"format_id": "140", "ext": "m4a", "url": "https://v/140", "vcodec": "none", "acodec": "mp4a.40.2",
             "filesize": 3_000_000, "protocol": "https"},
            {"format_id": "18", "ext": "mp4", "url": "https://v/18", "vcodec": "avc1.42001E",
             "acodec": "mp4a.40.2", "height": 360, "filesize": 9_000_000, "protocol": "https"},
            {"format_id": "137", "ext": "mp4", "url": "https://v/137", "vcodec": "avc1.640028", "acodec": "none",
             "height": 1080, "filesize_approx": 40_000_000, "protocol": "https"},
            {"format_id": "hls-720", "ext": "mp4", "url": "https://v/720.m3u8", "vcodec": "avc1", "acodec": "mp4a",
             "height": 720, "protocol": "m3u8_native"},
            {"format_id": "drm", "ext": "mp4", "url": "https://v/drm", "has_drm": True, "height": 2160},
        ],
    }
    r = from_ytdlp_info(info, "https://www.youtube.com/watch?v=x")
    assert [v.url for v in r.links if v.with_sound] == ["https://v/18"]
    assert [(v.url.rsplit("/", 1)[-1], v.kind) for v in r.links] == [
        ("18", "video"), ("137", "video only"), ("720.m3u8", "stream"), ("140", "audio only")]
    best = r.best()
    assert best.url == "https://v/18" and best.headers == {"User-Agent": "UA"} and best.source == "yt-dlp"
    assert r.links[1].size == 40_000_000 and r.links[2].ext == "m3u8"


def test_ytdlp_playlist_mapping():
    info = {"title": "List", "entries": [
        {"title": "Part 1", "formats": [{"url": "https://v/1", "ext": "mp4", "vcodec": "h264", "acodec": "aac"}]},
        None,
        {"title": "Part 2", "url": "https://v/2", "ext": "mp4"},
    ]}
    r = from_ytdlp_info(info, "https://x")
    assert [v.url for v in r.links] == ["https://v/1", "https://v/2"]
    assert r.links[0].note.startswith("Part 1")


@pytest.mark.skipif(not ytdlp_available(), reason="yt-dlp not installed")
def test_with_real_ytdlp(server):
    r = find_videos(server + "/watch/1")
    assert r.links and r.links[0].url.endswith(".mp4")
    assert not [v for v in r.links if "missing" in v.url]


def test_cli_find_and_download(server, tmp_path, capsys):
    assert cli.main(["find", "--no-ytdlp", "--best", server + "/watch/1"]) == 0
    assert capsys.readouterr().out.strip() == server + "/media/clip_720p.mp4"

    assert cli.main(["find", "--no-ytdlp", "--json", server + "/watch/2"]) == 0
    data = json.loads(capsys.readouterr().out)
    assert data["best"].endswith("hd_1080p.mp4?sig=a&e=1") and len(data["links"]) == 1
    assert cli.main(["find", "--no-ytdlp", "--json", "--all", server + "/watch/2"]) == 0
    assert len(json.loads(capsys.readouterr().out)["links"]) == 2

    assert cli.main(["find", "--no-ytdlp", "--download", "-d", str(tmp_path), server + "/watch/1"]) == 0
    assert (tmp_path / "Nile Sunset & Boats 720p.mp4").read_bytes() == VIDEO

    assert cli.main(["find", "--no-ytdlp", server + "/watch/none"]) == 1
