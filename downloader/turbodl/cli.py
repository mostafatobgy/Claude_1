"""Command-line interface: turbodl URL [URL ...]"""

from __future__ import annotations

import argparse
import shutil
import sys
import time

from . import __version__
from .engine import (
    DEFAULT_CONNECTIONS,
    MAX_CONNECTIONS,
    Download,
    human_size,
    human_time,
    parse_rate,
)


def _bar(stats, width: int) -> str:
    frac = stats.fraction
    if frac is None:
        return f"{human_size(stats.downloaded)} received"
    filled = int(frac * width)
    return "[" + "#" * filled + "-" * (width - filled) + f"] {frac * 100:5.1f}%"


def _line(dl: Download) -> str:
    st = dl.stats()
    cols = shutil.get_terminal_size((100, 20)).columns
    tail = (f" {human_size(st.downloaded)}/{human_size(st.total)}"
            f"  {human_size(st.speed)}/s  ETA {human_time(st.eta)}  x{st.connections}")
    width = max(10, min(40, cols - len(tail) - 12))
    return (_bar(st, width) + tail)[: cols - 1]


def download_one(dl: Download, quiet: bool) -> bool:
    dl.start()
    try:
        while True:
            dl.wait(0.5)
            if not quiet:
                sys.stderr.write("\r" + _line(dl) + "\033[K")
                sys.stderr.flush()
            if dl.state in ("done", "error", "paused", "cancelled"):
                break
    except KeyboardInterrupt:
        dl.pause()
        dl.wait()
        sys.stderr.write("\nPaused. Run the same command again to continue.\n")
        raise
    if not quiet:
        sys.stderr.write("\n")
    if dl.state == "done":
        print(dl.path)
        return True
    sys.stderr.write(f"Failed: {dl.name}: {dl.error}\n")
    return False


def find_main(argv: list[str]) -> int:
    from .finder import find_videos, ytdlp_available

    p = argparse.ArgumentParser(
        prog="turbodl find",
        description="Show the direct video links behind a web page (YouTube and other sites need yt-dlp).",
    )
    p.add_argument("url", metavar="PAGE_URL")
    p.add_argument("-b", "--best", action="store_true", help="print only the best link (for scripts)")
    p.add_argument("--json", action="store_true", help="print all results as JSON")
    p.add_argument("--download", action="store_true", help="download the best video with TurboDL")
    p.add_argument("-c", "--connections", type=int, default=DEFAULT_CONNECTIONS)
    p.add_argument("-d", "--dir", default=".", help="folder for --download")
    p.add_argument("--no-ytdlp", action="store_true", help="only use the built-in page scan")
    args = p.parse_args(argv)

    result = find_videos(args.url, use_ytdlp=not args.no_ytdlp)
    if args.json:
        import dataclasses
        import json
        print(json.dumps(dataclasses.asdict(result) | {"best": result.best() and result.best().url},
                         indent=2, ensure_ascii=False))
        return 0 if result.links else 1
    if not result.links:
        sys.stderr.write((result.message or "No video found.") + "\n")
        return 1
    best = result.best()
    if args.best:
        print(best.url)
    elif not args.download:
        if result.title:
            print(result.title)
        for i, v in enumerate(result.links, 1):
            star = "*" if v is best else " "
            size = human_size(v.size) if v.size else ""
            print(f"{star}{i:3}. {v.quality or '-':>6}  {v.kind:<11} {v.ext:<5} {size:>9}  {v.note}")
            print(f"       {v.url}")
        if not ytdlp_available() and not args.no_ytdlp:
            sys.stderr.write("Tip: pip install yt-dlp  - adds YouTube, Facebook, X, TikTok and 1,800+ sites.\n")
    if args.download:
        if not best.downloadable:
            sys.stderr.write("The best link is a stream playlist (m3u8/mpd), not a single file; "
                             "open it in VLC or use yt-dlp to save it.\n")
            return 1
        dl = Download(best.url, folder=args.dir, filename=result.filename_for(best),
                      connections=args.connections, headers=best.headers)
        try:
            return 0 if download_one(dl, quiet=False) else 1
        except KeyboardInterrupt:
            return 130
    return 0


def main(argv: list[str] | None = None) -> int:
    argv = sys.argv[1:] if argv is None else argv
    if argv and argv[0] == "find":
        return find_main(argv[1:])
    p = argparse.ArgumentParser(
        prog="turbodl",
        description="Download large files faster using several connections at once. "
                    "Interrupted downloads resume automatically. "
                    "To get the video links behind a web page, run: turbodl find PAGE_URL",
    )
    p.add_argument("urls", nargs="+", metavar="URL")
    p.add_argument("-c", "--connections", type=int, default=DEFAULT_CONNECTIONS,
                   help=f"parallel connections per file (1-{MAX_CONNECTIONS}, default {DEFAULT_CONNECTIONS})")
    p.add_argument("-d", "--dir", default=".", help="folder to save into (default: current folder)")
    p.add_argument("-o", "--output", help="file name (only with a single URL)")
    p.add_argument("-l", "--limit", default="", help="max total speed, e.g. 500K or 2M (default: unlimited)")
    p.add_argument("-H", "--header", action="append", default=[], metavar="'Name: value'",
                   help="extra HTTP header, e.g. a cookie; can be repeated")
    p.add_argument("--sha256", help="verify the finished file against this checksum")
    p.add_argument("--retries", type=int, default=8, help="retries per connection (default 8)")
    p.add_argument("-q", "--quiet", action="store_true", help="no progress bar")
    p.add_argument("-V", "--version", action="version", version=f"%(prog)s {__version__}")
    args = p.parse_args(argv)

    if args.output and len(args.urls) > 1:
        p.error("--output can only be used with one URL")
    headers = {}
    for h in args.header:
        name, sep, value = h.partition(":")
        if not sep:
            p.error(f"bad header {h!r}; use 'Name: value'")
        headers[name.strip()] = value.strip()
    try:
        rate = parse_rate(args.limit)
    except ValueError:
        p.error(f"bad speed limit {args.limit!r}")

    ok = True
    started = time.monotonic()
    try:
        for url in args.urls:
            dl = Download(url, folder=args.dir, filename=args.output, connections=args.connections,
                          headers=headers, rate_limit=rate, max_retries=args.retries, sha256=args.sha256)
            ok = download_one(dl, args.quiet) and ok
    except KeyboardInterrupt:
        return 130
    if not args.quiet and len(args.urls) > 1:
        sys.stderr.write(f"All done in {human_time(time.monotonic() - started)}\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
