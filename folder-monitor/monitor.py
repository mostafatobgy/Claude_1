"""مراقب أحجام الفولدرات — core engine + command-line mode.

Watches folder sizes and raises an alert when a folder grows by more than a
threshold within a short time window. Standard library only.

Usage:
    python monitor.py                 # run in the terminal using config.json
    python monitor.py --once          # scan once, print sizes, exit
    python gui.py                     # graphical window (recommended)
"""
from __future__ import annotations

import argparse
import json
import os
import platform
import subprocess
import sys
import threading
import time
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

APP_DIR = Path(__file__).resolve().parent
CONFIG_PATH = APP_DIR / "config.json"
LOG_PATH = APP_DIR / "alerts.log"

DEFAULT_CONFIG = {
    "folders": [],
    "interval_seconds": 60,      # how often to measure
    "window_minutes": 10,        # "short period" to compare against
    "threshold_mb": 500,         # alert if growth within window >= this ...
    "threshold_percent": 0,      # ... or >= this percent (0 = disabled)
    "cooldown_minutes": 15,      # don't repeat an alert for the same folder sooner
    "desktop_notifications": True,
    "ntfy_topic": "",            # optional: phone alerts via the free ntfy app
}


# ---------------------------------------------------------------- utilities

def human_size(num_bytes: float) -> str:
    sign = "-" if num_bytes < 0 else ""
    n = abs(float(num_bytes))
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return f"{sign}{n:.0f} {unit}" if unit == "B" else f"{sign}{n:.1f} {unit}"
        n /= 1024
    return f"{sign}{n:.1f} TB"


def load_config(path: Path = CONFIG_PATH) -> dict:
    cfg = dict(DEFAULT_CONFIG)
    if path.exists():
        try:
            cfg.update(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, json.JSONDecodeError) as e:
            print(f"تعذّر قراءة {path.name}: {e} — سيتم استخدام الإعدادات الافتراضية", file=sys.stderr)
    return cfg


def save_config(cfg: dict, path: Path = CONFIG_PATH) -> None:
    path.write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8")


def scan_folder(root: str) -> tuple[int, dict[str, int]]:
    """Return (total_bytes, {top-level child name: bytes}).

    Does not follow symlinks; unreadable files/folders are skipped silently so
    a single permission error never stops the monitor.
    """
    children: dict[str, int] = {}
    total = 0
    try:
        top = list(os.scandir(root))
    except OSError:
        return 0, {}
    for entry in top:
        size = _entry_size(entry)
        children[entry.name] = size
        total += size
    return total, children


def _entry_size(entry: os.DirEntry) -> int:
    try:
        if entry.is_symlink():
            return 0
        if entry.is_file(follow_symlinks=False):
            return entry.stat(follow_symlinks=False).st_size
        if entry.is_dir(follow_symlinks=False):
            return _dir_size(entry.path)
    except OSError:
        pass
    return 0


def _dir_size(path: str) -> int:
    total = 0
    stack = [path]
    while stack:
        current = stack.pop()
        try:
            with os.scandir(current) as it:
                for e in it:
                    try:
                        if e.is_symlink():
                            continue
                        if e.is_file(follow_symlinks=False):
                            total += e.stat(follow_symlinks=False).st_size
                        elif e.is_dir(follow_symlinks=False):
                            stack.append(e.path)
                    except OSError:
                        continue
        except OSError:
            continue
    return total


# ------------------------------------------------------------ growth logic

@dataclass
class Sample:
    t: float
    size: int
    children: dict[str, int]


@dataclass
class FolderState:
    path: str
    samples: list[Sample] = field(default_factory=list)
    last_alert: float = float("-inf")  # never alerted yet
    error: str = ""

    @property
    def current(self) -> int:
        return self.samples[-1].size if self.samples else 0

    def add(self, sample: Sample, window_s: float) -> None:
        self.samples.append(sample)
        # keep one sample at/just before the window start so the window is fully covered
        cutoff = sample.t - window_s
        while len(self.samples) > 2 and self.samples[1].t <= cutoff:
            self.samples.pop(0)

    def growth(self) -> tuple[int, Sample | None]:
        """Growth from the smallest size seen in the window to now."""
        if len(self.samples) < 2:
            return 0, None
        base = min(self.samples[:-1], key=lambda s: s.size)
        return self.current - base.size, base


@dataclass
class Alert:
    path: str
    growth: int
    percent: float
    minutes: float
    size: int
    top_growers: list[tuple[str, int]]

    def title(self) -> str:
        return f"⚠ زيادة سريعة في: {Path(self.path).name or self.path}"

    def message(self) -> str:
        span = f"{self.minutes:.0f} دقيقة" if self.minutes >= 1 else f"{self.minutes * 60:.0f} ثانية"
        msg = (f"زاد بمقدار {human_size(self.growth)} ({self.percent:.0f}%) "
               f"خلال {span} — الحجم الآن {human_size(self.size)}")
        if self.top_growers:
            parts = ", ".join(f"{n} (+{human_size(g)})" for n, g in self.top_growers)
            msg += f"\nأكثر ما زاد: {parts}"
        return msg


def check_growth(state: FolderState, cfg: dict, now: float) -> Alert | None:
    growth, base = state.growth()
    if base is None or growth <= 0:
        return None
    mb_hit = cfg["threshold_mb"] > 0 and growth >= cfg["threshold_mb"] * 1024 * 1024
    percent = (growth / base.size * 100) if base.size else 100.0
    pct_hit = cfg["threshold_percent"] > 0 and percent >= cfg["threshold_percent"]
    if not (mb_hit or pct_hit):
        return None
    if now - state.last_alert < cfg["cooldown_minutes"] * 60:
        return None
    state.last_alert = now
    latest = state.samples[-1]
    deltas = [(name, size - base.children.get(name, 0)) for name, size in latest.children.items()]
    top = sorted((d for d in deltas if d[1] > 0), key=lambda d: d[1], reverse=True)[:3]
    return Alert(state.path, growth, percent, (now - base.t) / 60, latest.size, top)


# ---------------------------------------------------------------- notifying

def desktop_notify(title: str, message: str) -> None:
    system = platform.system()
    try:
        if system == "Windows":
            ps = (
                "Add-Type -AssemblyName System.Windows.Forms;"
                "$n=New-Object System.Windows.Forms.NotifyIcon;"
                "$n.Icon=[System.Drawing.SystemIcons]::Warning;$n.Visible=$true;"
                f"$n.ShowBalloonTip(10000,'{_ps(title)}','{_ps(message)}','Warning');"
                "Start-Sleep -Seconds 11;$n.Dispose()"
            )
            subprocess.Popen(["powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", ps],
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        elif system == "Darwin":
            script = f'display notification {json.dumps(message)} with title {json.dumps(title)} sound name "Glass"'
            subprocess.Popen(["osascript", "-e", script])
        else:
            subprocess.Popen(["notify-send", "-u", "critical", title, message])
    except OSError:
        pass  # no notifier available — the alert is still logged and shown in the app


def _ps(s: str) -> str:
    return s.replace("'", "''").replace("\n", " | ")


def ntfy_notify(topic: str, title: str, message: str) -> None:
    def send():
        try:
            payload = {"topic": topic, "title": title, "message": message,
                       "priority": 4, "tags": ["warning"]}
            req = urllib.request.Request(
                "https://ntfy.sh/", data=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"})
            urllib.request.urlopen(req, timeout=10).close()
        except Exception as e:  # network problems must never stop monitoring
            print(f"ntfy failed: {e}", file=sys.stderr)
    threading.Thread(target=send, daemon=True).start()


def log_alert(alert: Alert) -> None:
    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
    try:
        with LOG_PATH.open("a", encoding="utf-8") as f:
            f.write(f"[{stamp}] {alert.path}: {alert.message().replace(chr(10), ' | ')}\n")
    except OSError:
        pass


def dispatch(alert: Alert, cfg: dict) -> None:
    log_alert(alert)
    if cfg.get("desktop_notifications", True):
        desktop_notify(alert.title(), alert.message())
    if cfg.get("ntfy_topic"):
        ntfy_notify(cfg["ntfy_topic"], alert.title(), alert.message())


# ------------------------------------------------------------------ monitor

class Monitor:
    """Scans the configured folders on a background thread."""

    def __init__(self, cfg: dict, on_update=None, on_alert=None):
        self.cfg = cfg
        self.states: dict[str, FolderState] = {}
        self.on_update = on_update or (lambda states: None)
        self.on_alert = on_alert or (lambda alert: None)
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.lock = threading.Lock()

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> None:
        if self.running:
            return
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def scan_once(self) -> list[Alert]:
        alerts = []
        window_s = self.cfg["window_minutes"] * 60
        for path in list(self.cfg["folders"]):
            if self._stop.is_set() and self.running:
                break
            now = time.time()
            with self.lock:
                state = self.states.setdefault(path, FolderState(path))
            if not os.path.isdir(path):
                state.error = "الفولدر غير موجود"
                continue
            state.error = ""
            total, children = scan_folder(path)
            with self.lock:
                state.add(Sample(now, total, children), window_s)
                alert = check_growth(state, self.cfg, now)
            if alert:
                alerts.append(alert)
                dispatch(alert, self.cfg)
                self.on_alert(alert)
        with self.lock:
            for gone in set(self.states) - set(self.cfg["folders"]):
                del self.states[gone]
        self.on_update(self.states)
        return alerts

    def _loop(self) -> None:
        while not self._stop.is_set():
            started = time.time()
            self.scan_once()
            wait = max(1.0, self.cfg["interval_seconds"] - (time.time() - started))
            self._stop.wait(wait)


# --------------------------------------------------------------------- CLI

def main() -> None:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description="مراقب أحجام الفولدرات")
    ap.add_argument("--once", action="store_true", help="افحص مرة واحدة واطبع الأحجام")
    ap.add_argument("--config", type=Path, default=CONFIG_PATH)
    args = ap.parse_args()

    cfg = load_config(args.config)
    if not cfg["folders"]:
        print(f"لا توجد فولدرات للمراقبة. أضفها في {args.config} أو استخدم: python gui.py")
        sys.exit(1)

    def show(states):
        print(time.strftime("\n%H:%M:%S"))
        for st in states.values():
            g, _ = st.growth()
            info = st.error or f"{human_size(st.current):>10}  (+{human_size(max(g, 0))} في النافذة)"
            print(f"  {st.path}: {info}")

    def alert(a: Alert):
        print(f"\n\a*** {a.title()}\n    {a.message()}\n")

    mon = Monitor(cfg, on_update=show, on_alert=alert)
    if args.once:
        mon.scan_once()
        return
    print(f"المراقبة تعمل: كل {cfg['interval_seconds']} ث، تنبيه عند زيادة ≥ {cfg['threshold_mb']} MB "
          f"خلال {cfg['window_minutes']} دقيقة. (Ctrl+C للإيقاف)")
    try:
        mon._loop()
    except KeyboardInterrupt:
        print("\nتم الإيقاف.")


if __name__ == "__main__":
    main()
