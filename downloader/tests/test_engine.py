import hashlib
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from turbodl import engine
from turbodl.engine import Download, parse_rate, safe_filename

DATA = os.urandom(6 * 1024 * 1024 + 123)


class Handler(BaseHTTPRequestHandler):
    ranges = True
    per_conn_rate = 0  # bytes/s per connection, 0 = unlimited
    slow_first = False  # the connection starting at byte 0 is 4x slower
    fail_once: set = set()
    requests: list = []

    def log_message(self, *a):
        pass

    def do_GET(self):
        type(self).requests.append(self.headers.get("Range"))
        if self.path.startswith("/redirect"):
            self.send_response(302)
            self.send_header("Location", "/files/movie.mp4")
            self.end_headers()
            return
        start, end = 0, len(DATA) - 1
        rng = self.headers.get("Range")
        if self.ranges and rng:
            m = re.match(r"bytes=(\d+)-(\d*)", rng)
            start = int(m.group(1))
            end = min(int(m.group(2)) if m.group(2) else end, len(DATA) - 1)
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(DATA)}")
        else:
            self.send_response(200)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("ETag", '"v1"')
        if self.path.endswith("?cd"):
            self.send_header("Content-Disposition", "attachment; filename*=UTF-8''my%20film.mkv")
        self.end_headers()
        body = DATA[start:end + 1]
        # Drop the connection half-way once for each listed start offset.
        if start in self.fail_once and len(body) > 1000:
            self.fail_once.discard(start)
            self.wfile.write(body[:len(body) // 2])
            return
        step = 64 * 1024
        for i in range(0, len(body), step):
            try:
                self.wfile.write(body[i:i + step])
            except (BrokenPipeError, ConnectionResetError):
                return
            if self.per_conn_rate:
                slow = 4 if self.slow_first and start == 0 else 1
                time.sleep(slow * step / self.per_conn_rate)


@pytest.fixture
def server():
    Handler.ranges = True
    Handler.per_conn_rate = 0
    Handler.slow_first = False
    Handler.fail_once = set()
    Handler.requests = []
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    srv.daemon_threads = True
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield f"http://127.0.0.1:{srv.server_address[1]}"
    srv.shutdown()
    srv.server_close()


def read(path):
    with open(path, "rb") as f:
        return f.read()


def test_parallel_download(server, tmp_path):
    dl = Download(server + "/files/movie.mp4", folder=str(tmp_path), connections=6)
    dl.run()
    assert dl.state == "done"
    assert os.path.basename(dl.path) == "movie.mp4"
    assert read(dl.path) == DATA
    assert not os.path.exists(dl.path + ".part")
    assert not os.path.exists(dl.path + ".part.turbodl")
    assert len([r for r in Handler.requests if r and r != "bytes=0-0"]) >= 6


def test_faster_than_single_connection(server, tmp_path):
    Handler.per_conn_rate = 3 * 1024 * 1024  # like a CDN that caps each connection
    t = time.monotonic()
    Download(server + "/files/a.bin", folder=str(tmp_path), connections=1).run()
    single = time.monotonic() - t
    t = time.monotonic()
    dl = Download(server + "/files/b.bin", folder=str(tmp_path), connections=8)
    dl.run()
    multi = time.monotonic() - t
    assert read(dl.path) == DATA
    assert multi < single / 2.5, (single, multi)


def test_work_stealing_keeps_connections_busy(server, tmp_path, monkeypatch):
    monkeypatch.setattr(engine, "MIN_SPLIT", 256 * 1024)
    Handler.per_conn_rate = 4 * 1024 * 1024
    Handler.slow_first = True
    dl = Download(server + "/f.bin", folder=str(tmp_path), connections=2)
    dl.run()
    assert read(dl.path) == DATA
    assert len(dl.segments) > 2  # finished connections took over others' work


def test_server_without_ranges(server, tmp_path):
    Handler.ranges = False
    dl = Download(server + "/plain.bin", folder=str(tmp_path), connections=8)
    dl.run()
    assert not dl.info.resumable
    assert read(dl.path) == DATA


def test_retry_after_dropped_connection(server, tmp_path):
    Handler.fail_once = {0}
    dl = Download(server + "/x.bin", folder=str(tmp_path), connections=4)
    dl.run()
    assert read(dl.path) == DATA


def test_pause_and_resume_in_new_process(server, tmp_path):
    Handler.per_conn_rate = 1024 * 1024
    dl = Download(server + "/files/big.iso", folder=str(tmp_path), connections=4)
    dl.start()
    time.sleep(1.0)
    dl.pause()
    dl.wait()
    assert dl.state == "paused"
    done_before = dl.stats().downloaded
    assert 0 < done_before < len(DATA)
    assert os.path.exists(dl.path + ".part.turbodl")

    # A brand-new Download object (as after restarting the app) continues the same file.
    Handler.per_conn_rate = 0
    Handler.requests = []
    dl2 = Download(server + "/files/big.iso", folder=str(tmp_path), connections=4)
    dl2.run()
    assert dl2.path == dl.path
    assert read(dl2.path) == DATA
    starts = [int(re.match(r"bytes=(\d+)", r).group(1)) for r in Handler.requests if r and r != "bytes=0-0"]
    assert 0 not in starts  # nothing was downloaded twice from the beginning


def test_cancel_deletes_partial(server, tmp_path):
    Handler.per_conn_rate = 512 * 1024
    dl = Download(server + "/c.bin", folder=str(tmp_path), connections=2)
    dl.start()
    time.sleep(0.5)
    dl.cancel()
    dl.wait()
    assert dl.state == "cancelled"
    assert os.listdir(tmp_path) == []


def test_does_not_overwrite_existing_file(server, tmp_path):
    (tmp_path / "movie.mp4").write_bytes(b"mine")
    dl = Download(server + "/movie.mp4", folder=str(tmp_path))
    dl.run()
    assert os.path.basename(dl.path) == "movie (1).mp4"
    assert (tmp_path / "movie.mp4").read_bytes() == b"mine"


def test_redirect_and_content_disposition(server, tmp_path):
    dl = Download(server + "/redirect", folder=str(tmp_path))
    dl.run()
    assert os.path.basename(dl.path) == "movie.mp4"
    dl = Download(server + "/get?cd", folder=str(tmp_path))
    dl.run()
    assert os.path.basename(dl.path) == "my film.mkv"


def test_checksum(server, tmp_path):
    good = hashlib.sha256(DATA).hexdigest()
    dl = Download(server + "/ok.bin", folder=str(tmp_path), sha256=good)
    dl.run()
    assert dl.state == "done"
    dl = Download(server + "/bad.bin", folder=str(tmp_path), sha256="0" * 64)
    with pytest.raises(engine.DownloadError):
        dl.run()
    assert dl.state == "error"


def test_network_error_is_reported(tmp_path, monkeypatch):
    monkeypatch.setattr(Download, "probe", lambda self: (_ for _ in ()).throw(
        engine.urllib.error.URLError("refused")))
    dl = Download("http://127.0.0.1:1/none", folder=str(tmp_path))
    with pytest.raises(engine.urllib.error.URLError):
        dl.run()
    assert dl.state == "error" and "refused" in dl.error


def test_helpers():
    assert parse_rate("") == 0
    assert parse_rate("500K") == 500 * 1024
    assert parse_rate("2MB/s") == 2 * 1024 * 1024
    assert safe_filename('a/b\\c:"d".mp4') == "c__d_.mp4"
