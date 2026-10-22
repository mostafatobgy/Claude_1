"""Desktop window for TurboDL (Tkinter, included with Python on Windows and macOS)."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from . import __version__
from .engine import DEFAULT_CONNECTIONS, MAX_CONNECTIONS, Download, human_size, human_time, parse_rate
from .finder import FindResult, find_videos, ytdlp_available

CONFIG_DIR = os.path.join(os.path.expanduser("~"), ".turbodl")
CONFIG_FILE = os.path.join(CONFIG_DIR, "settings.json")
REFRESH_MS = 400

STATUS_TEXT = {
    "queued": "Waiting",
    "connecting": "Connecting…",
    "downloading": "Downloading",
    "verifying": "Checking file…",
    "paused": "Paused",
    "done": "Completed",
    "error": "Error",
    "cancelled": "Cancelled",
}


def default_folder() -> str:
    downloads = os.path.join(os.path.expanduser("~"), "Downloads")
    return downloads if os.path.isdir(downloads) else os.path.expanduser("~")


def open_path(path: str) -> None:
    if sys.platform.startswith("win"):
        os.startfile(path)  # noqa: S606
    elif sys.platform == "darwin":
        subprocess.Popen(["open", path])
    else:
        subprocess.Popen(["xdg-open", path])


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.downloads: dict[str, Download] = {}  # tree item id -> download
        self.settings = self._load_settings()

        root.title(f"TurboDL {__version__} — Download Accelerator")
        root.geometry("980x520")
        root.minsize(760, 380)
        style = ttk.Style()
        if "clam" in style.theme_names() and sys.platform.startswith("linux"):
            style.theme_use("clam")
        style.configure("Treeview", rowheight=26)
        style.configure("Accent.TButton", font=("TkDefaultFont", 10, "bold"))

        self._build()
        self._restore_queue()
        root.protocol("WM_DELETE_WINDOW", self._on_close)
        root.after(REFRESH_MS, self._tick)

    # ------------------------------------------------------------------ UI

    def _build(self) -> None:
        pad = {"padx": 8, "pady": 4}
        top = ttk.Frame(self.root)
        top.pack(fill="x", **pad)

        ttk.Label(top, text="Link:").grid(row=0, column=0, sticky="w")
        self.url_var = tk.StringVar()
        url_entry = ttk.Entry(top, textvariable=self.url_var)
        url_entry.grid(row=0, column=1, columnspan=5, sticky="ew", padx=4)
        url_entry.bind("<Return>", lambda e: self.add())
        ttk.Button(top, text="Paste", command=self.paste).grid(row=0, column=6, padx=2)
        ttk.Button(top, text="Download", style="Accent.TButton", command=self.add).grid(row=0, column=7, padx=2)
        ttk.Button(top, text="Find video…", command=self.find_video).grid(row=0, column=8, padx=2)

        ttk.Label(top, text="Save to:").grid(row=1, column=0, sticky="w", pady=(6, 0))
        self.folder_var = tk.StringVar(value=self.settings.get("folder") or default_folder())
        ttk.Entry(top, textvariable=self.folder_var).grid(row=1, column=1, sticky="ew", padx=4, pady=(6, 0))
        ttk.Button(top, text="Browse…", command=self.browse).grid(row=1, column=2, padx=2, pady=(6, 0))

        ttk.Label(top, text="Connections:").grid(row=1, column=3, sticky="e", padx=(12, 2), pady=(6, 0))
        self.conn_var = tk.IntVar(value=self.settings.get("connections", DEFAULT_CONNECTIONS))
        ttk.Spinbox(top, from_=1, to=MAX_CONNECTIONS, width=4, textvariable=self.conn_var).grid(
            row=1, column=4, sticky="w", pady=(6, 0))

        ttk.Label(top, text="Speed limit:").grid(row=1, column=5, sticky="e", padx=(12, 2), pady=(6, 0))
        self.limit_var = tk.StringVar(value=self.settings.get("limit", ""))
        ttk.Entry(top, textvariable=self.limit_var, width=8).grid(row=1, column=6, sticky="w", pady=(6, 0))
        ttk.Label(top, text="(e.g. 2M, empty = none)", foreground="gray").grid(
            row=1, column=7, sticky="w", pady=(6, 0))
        top.columnconfigure(1, weight=1)

        cols = ("size", "progress", "speed", "eta", "conn", "status")
        frame = ttk.Frame(self.root)
        frame.pack(fill="both", expand=True, **pad)
        self.tree = ttk.Treeview(frame, columns=cols, show="tree headings", selectmode="extended")
        self.tree.heading("#0", text="File")
        self.tree.column("#0", width=270, anchor="w")
        for col, title, width in (
            ("size", "Size", 90), ("progress", "Progress", 180), ("speed", "Speed", 95),
            ("eta", "Time left", 80), ("conn", "Conn.", 55), ("status", "Status", 160),
        ):
            self.tree.heading(col, text=title)
            self.tree.column(col, width=width, anchor="center", stretch=col == "status")
        scroll = ttk.Scrollbar(frame, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        self.tree.bind("<Double-1>", lambda e: self.open_file())
        self.tree.tag_configure("done", foreground="#1a7f37")
        self.tree.tag_configure("error", foreground="#cf222e")

        bottom = ttk.Frame(self.root)
        bottom.pack(fill="x", **pad)
        for text, cmd in (("Pause", self.pause), ("Resume", self.resume), ("Cancel", self.cancel),
                          ("Remove from list", self.remove), ("Open file", self.open_file),
                          ("Open folder", self.open_folder)):
            ttk.Button(bottom, text=text, command=cmd).pack(side="left", padx=2)
        self.total_var = tk.StringVar()
        ttk.Label(bottom, textvariable=self.total_var).pack(side="right")

    # ------------------------------------------------------------- actions

    def paste(self) -> None:
        try:
            self.url_var.set(self.root.clipboard_get().strip())
        except tk.TclError:
            pass

    def browse(self) -> None:
        folder = filedialog.askdirectory(initialdir=self.folder_var.get() or default_folder())
        if folder:
            self.folder_var.set(folder)

    def add(self) -> None:
        urls = [u for u in self.url_var.get().split() if u]
        if not urls:
            self.paste()
            urls = [u for u in self.url_var.get().split() if u]
        bad = [u for u in urls if not u.lower().startswith(("http://", "https://"))]
        if not urls or bad:
            messagebox.showwarning("TurboDL", "Please enter a link starting with http:// or https://")
            return
        try:
            rate = parse_rate(self.limit_var.get())
        except ValueError:
            messagebox.showwarning("TurboDL", "Speed limit should look like 500K or 2M.")
            return
        for url in urls:
            self._add_download(url, self.folder_var.get(), rate=rate)
        self.url_var.set("")
        self._save_settings()

    def find_video(self) -> None:
        url = self.url_var.get().strip()
        if not url:
            self.paste()
            url = self.url_var.get().strip()
        if not url:
            messagebox.showinfo("TurboDL", "Paste the address of the page that has the video, "
                                           "then click Find video.")
            return
        FinderDialog(self, url)

    def _add_download(self, url: str, folder: str, path: str | None = None, rate: int = 0,
                      start: bool = True, headers: dict | None = None) -> None:
        dl = Download(url, folder=folder, connections=self._connections(), rate_limit=rate, headers=headers)
        if path:
            dl.requested_name = os.path.basename(path)
        item = self.tree.insert("", "end", text=dl.name, values=("?", "", "", "", "", "Waiting"))
        self.downloads[item] = dl
        if start:
            dl.start()
        else:
            dl.state = "paused"

    def _connections(self) -> int:
        try:
            return max(1, min(MAX_CONNECTIONS, int(self.conn_var.get())))
        except (tk.TclError, ValueError):
            return DEFAULT_CONNECTIONS

    def _selected(self) -> list[tuple[str, Download]]:
        return [(i, self.downloads[i]) for i in self.tree.selection() if i in self.downloads]

    def pause(self) -> None:
        for _, dl in self._selected():
            dl.pause()

    def resume(self) -> None:
        for _, dl in self._selected():
            if dl.state in ("paused", "error"):
                dl.connections = self._connections()
                dl.start()

    def cancel(self) -> None:
        sel = self._selected()
        if sel and messagebox.askyesno("TurboDL", "Cancel the selected downloads and delete their partial files?"):
            for _, dl in sel:
                if dl.state != "done":
                    dl.cancel()

    def remove(self) -> None:
        for item, dl in self._selected():
            if dl.state not in ("done", "cancelled", "error", "paused"):
                dl.pause()
            self.tree.delete(item)
            del self.downloads[item]
        self._save_settings()

    def open_file(self) -> None:
        for _, dl in self._selected():
            if dl.state == "done" and dl.path and os.path.exists(dl.path):
                open_path(dl.path)

    def open_folder(self) -> None:
        sel = self._selected()
        folder = sel[0][1].folder if sel else self.folder_var.get()
        if os.path.isdir(folder):
            open_path(folder)

    # ------------------------------------------------------------ refresh

    def _tick(self) -> None:
        total_speed = 0.0
        for item, dl in self.downloads.items():
            st = dl.stats()
            total_speed += st.speed
            frac = st.fraction
            if frac is not None:
                blocks = int(frac * 10)
                progress = "█" * blocks + "░" * (10 - blocks) + f" {frac * 100:4.1f}%"
            else:
                progress = human_size(st.downloaded)
            active = st.state in ("downloading", "connecting")
            status = STATUS_TEXT.get(st.state, st.state)
            if st.state == "error" and st.error:
                status = f"Error: {st.error}"
            self.tree.item(item, text=dl.name, tags=(st.state,), values=(
                human_size(st.total),
                progress,
                f"{human_size(st.speed)}/s" if active else "",
                human_time(st.eta) if active else "",
                st.connections or "",
                status,
            ))
        running = sum(1 for d in self.downloads.values() if d.state in ("downloading", "connecting"))
        self.total_var.set(f"{running} active   ·   total {human_size(total_speed)}/s")
        self.root.after(REFRESH_MS, self._tick)

    # ---------------------------------------------------------- settings

    def _load_settings(self) -> dict:
        try:
            with open(CONFIG_FILE, encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return {}

    def _save_settings(self) -> None:
        queue = [
            {"url": d.url, "folder": d.folder, "path": d.path or d.requested_name,
             "headers": d.extra_headers}
            for d in self.downloads.values() if d.state not in ("done", "cancelled")
        ]
        data = {
            "folder": self.folder_var.get(),
            "connections": self._connections(),
            "limit": self.limit_var.get(),
            "queue": queue,
        }
        try:
            os.makedirs(CONFIG_DIR, exist_ok=True)
            with open(CONFIG_FILE, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2)
        except OSError:
            pass

    def _restore_queue(self) -> None:
        """Unfinished downloads from last time come back paused; press Resume to continue."""
        for entry in self.settings.get("queue", []):
            if entry.get("url"):
                self._add_download(entry["url"], entry.get("folder") or default_folder(),
                                   path=entry.get("path"), start=False, headers=entry.get("headers"))

    def _on_close(self) -> None:
        active = [d for d in self.downloads.values() if d.state in ("downloading", "connecting", "verifying")]
        if active and not messagebox.askyesno(
                "TurboDL", "Downloads are still running. Pause them and quit?\n"
                           "They will continue from where they stopped next time."):
            return
        for d in active:
            d.pause()
        for d in active:
            d.wait(5)
        self._save_settings()
        self.root.destroy()


class FinderDialog:
    """Shows the video links found behind a page; copy one or download it."""

    def __init__(self, app: App, url: str):
        self.app = app
        self.result: FindResult | None = None
        self.win = win = tk.Toplevel(app.root)
        win.title("Find video")
        win.geometry("860x380")
        win.transient(app.root)

        self.status = tk.StringVar(value=f"Looking for videos in {url} …")
        ttk.Label(win, textvariable=self.status, wraplength=830).pack(fill="x", padx=10, pady=(10, 4))

        frame = ttk.Frame(win)
        frame.pack(fill="both", expand=True, padx=10)
        cols = ("quality", "kind", "ext", "size", "note")
        self.tree = ttk.Treeview(frame, columns=cols, show="headings", selectmode="browse")
        for col, title, width in (("quality", "Quality", 70), ("kind", "Type", 100), ("ext", "Format", 60),
                                  ("size", "Size", 85), ("note", "Details / link", 500)):
            self.tree.heading(col, text=title)
            self.tree.column(col, width=width, anchor="w" if col == "note" else "center", stretch=col == "note")
        scroll = ttk.Scrollbar(frame, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.pack(side="left", fill="both", expand=True)
        scroll.pack(side="right", fill="y")
        self.tree.bind("<Double-1>", lambda e: self.download())

        buttons = ttk.Frame(win)
        buttons.pack(fill="x", padx=10, pady=8)
        self.dl_btn = ttk.Button(buttons, text="Download", style="Accent.TButton", command=self.download,
                                 state="disabled")
        self.dl_btn.pack(side="left", padx=2)
        self.copy_btn = ttk.Button(buttons, text="Copy link", command=self.copy, state="disabled")
        self.copy_btn.pack(side="left", padx=2)
        ttk.Button(buttons, text="Close", command=win.destroy).pack(side="right", padx=2)
        if not ytdlp_available():
            ttk.Label(buttons, foreground="gray",
                      text="Tip: install yt-dlp for YouTube, Facebook, X, TikTok and 1,800+ more sites"
                      ).pack(side="left", padx=10)

        threading.Thread(target=self._search, args=(url,), daemon=True).start()

    def _search(self, url: str) -> None:
        try:
            result = find_videos(url)
        except Exception as exc:  # noqa: BLE001
            result = FindResult(page_url=url, message=f"Something went wrong: {exc}")
        try:
            self.win.after(0, self._show, result)
        except (tk.TclError, RuntimeError):
            pass  # window was closed while searching

    def _show(self, result: FindResult) -> None:
        if not self.win.winfo_exists():
            return
        self.result = result
        if not result.links:
            self.status.set(result.message or "No video found on this page.")
            return
        count = len(result.links)
        self.status.set(f"{result.title or 'Video'} — found {count} link{'s' * (count != 1)}. "
                        "Pick one and click Download, or copy its link.")
        best = result.best()
        for i, v in enumerate(result.links):
            self.tree.insert("", "end", iid=str(i), values=(
                v.quality or "-", v.kind, v.ext, human_size(v.size) if v.size else "",
                (v.note + "  ·  " if v.note else "") + v.url))
            if v is best:
                self.tree.selection_set(str(i))
                self.tree.see(str(i))
        self.dl_btn.configure(state="normal")
        self.copy_btn.configure(state="normal")

    def _selected(self):
        sel = self.tree.selection()
        if not (sel and self.result):
            return None
        return self.result.links[int(sel[0])]

    def copy(self) -> None:
        link = self._selected()
        if link:
            self.win.clipboard_clear()
            self.win.clipboard_append(link.url)
            self.status.set("Link copied. Note: some sites' links only work for a few hours.")

    def download(self) -> None:
        link = self._selected()
        if not link or not self.result:
            return
        if not link.downloadable:
            messagebox.showinfo("TurboDL", "This is a stream playlist (made of many small pieces), not a "
                                           "single file. Copy the link and open it in VLC, or save it "
                                           "with yt-dlp.", parent=self.win)
            return
        try:
            rate = parse_rate(self.app.limit_var.get())
        except ValueError:
            rate = 0
        self.app._add_download(link.url, self.app.folder_var.get(), path=self.result.filename_for(link),
                               rate=rate, headers=link.headers)
        self.app.url_var.set("")
        self.app._save_settings()
        self.win.destroy()


def main() -> int:
    root = tk.Tk()
    App(root)
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
