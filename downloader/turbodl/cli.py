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


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        prog="turbodl",
        description="Download large files faster using several connections at once. "
                    "Interrupted downloads resume automatically.",
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
