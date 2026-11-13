"""مراقب أحجام الفولدرات — graphical window (Tkinter, ships with Python)."""
from __future__ import annotations

import os
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from monitor import LOG_PATH, Alert, Monitor, human_size, load_config, save_config

SETTINGS = [
    ("interval_seconds", "الفحص كل (ثانية)"),
    ("window_minutes", "الفترة القصيرة (دقيقة)"),
    ("threshold_mb", "نبّهني عند زيادة ≥ (MB)"),
    ("threshold_percent", "أو زيادة ≥ (%)  0=معطّل"),
    ("cooldown_minutes", "عدم تكرار التنبيه لمدة (دقيقة)"),
]


class App:
    def __init__(self, root: tk.Tk):
        self.root = root
        self.cfg = load_config()
        self.monitor = Monitor(self.cfg,
                               on_update=lambda s: root.after(0, self.refresh),
                               on_alert=lambda a: root.after(0, self.show_alert, a))
        root.title("مراقب أحجام الفولدرات")
        root.geometry("900x560")
        root.minsize(700, 420)
        self._build()
        self.refresh()
        if self.cfg["folders"]:
            self.toggle()
        root.protocol("WM_DELETE_WINDOW", self.on_close)

    # ------------------------------------------------------------ layout
    def _build(self):
        pad = {"padx": 6, "pady": 4}
        bar = ttk.Frame(self.root)
        bar.pack(fill="x", **pad)
        ttk.Button(bar, text="➕ إضافة فولدر", command=self.add_folder).pack(side="right", **pad)
        ttk.Button(bar, text="➖ حذف المحدد", command=self.remove_folder).pack(side="right", **pad)
        self.toggle_btn = ttk.Button(bar, text="▶ بدء المراقبة", command=self.toggle)
        self.toggle_btn.pack(side="right", **pad)
        ttk.Button(bar, text="🔄 فحص الآن", command=self.scan_now).pack(side="right", **pad)
        ttk.Button(bar, text="📄 سجل التنبيهات", command=self.open_log).pack(side="left", **pad)
        self.status = ttk.Label(bar, text="")
        self.status.pack(side="left", **pad)

        cols = ("status", "growth", "size", "path")
        self.tree = ttk.Treeview(self.root, columns=cols, show="headings", height=10)
        for col, title, width, anchor in (
            ("path", "الفولدر", 430, "e"), ("size", "الحجم الحالي", 110, "center"),
            ("growth", "الزيادة في الفترة", 130, "center"), ("status", "الحالة", 150, "center")):
            self.tree.heading(col, text=title)
            self.tree.column(col, width=width, anchor=anchor)
        self.tree.tag_configure("alert", background="#ffd6d6")
        self.tree.tag_configure("error", foreground="#999999")
        self.tree.pack(fill="both", expand=True, **pad)

        box = ttk.LabelFrame(self.root, text="الإعدادات")
        box.pack(fill="x", **pad)
        self.vars: dict[str, tk.StringVar] = {}
        for i, (key, label) in enumerate(SETTINGS):
            r, c = divmod(i, 3)
            ttk.Label(box, text=label).grid(row=r, column=5 - c * 2, sticky="e", **pad)
            v = tk.StringVar(value=str(self.cfg[key]))
            ttk.Entry(box, textvariable=v, width=8, justify="center").grid(row=r, column=4 - c * 2, **pad)
            self.vars[key] = v
        ttk.Label(box, text="قناة ntfy للهاتف (اختياري)").grid(row=2, column=5, sticky="e", **pad)
        self.ntfy = tk.StringVar(value=self.cfg.get("ntfy_topic", ""))
        ttk.Entry(box, textvariable=self.ntfy, width=28).grid(row=2, column=2, columnspan=3, sticky="we", **pad)
        self.desktop = tk.BooleanVar(value=self.cfg.get("desktop_notifications", True))
        ttk.Checkbutton(box, text="إشعارات سطح المكتب", variable=self.desktop).grid(row=2, column=1, **pad)
        ttk.Button(box, text="💾 حفظ", command=self.save_settings).grid(row=2, column=0, **pad)

        self.alerts = tk.Listbox(self.root, height=5, justify="right")
        self.alerts.pack(fill="x", **pad)

    # ----------------------------------------------------------- actions
    def add_folder(self):
        path = filedialog.askdirectory(title="اختر فولدر للمراقبة")
        if not path:
            return
        path = os.path.normpath(path)
        if path in self.cfg["folders"]:
            return
        self.cfg["folders"].append(path)
        save_config(self.cfg)
        self.refresh()
        if not self.monitor.running:
            self.toggle()

    def remove_folder(self):
        for item in self.tree.selection():
            if item in self.cfg["folders"]:
                self.cfg["folders"].remove(item)
        save_config(self.cfg)
        self.refresh()

    def save_settings(self):
        try:
            for key, v in self.vars.items():
                value = float(v.get())
                if value < 0 or (key in ("interval_seconds", "window_minutes") and value <= 0):
                    raise ValueError
                self.cfg[key] = int(value) if value.is_integer() else value
        except ValueError:
            messagebox.showerror("خطأ", "أدخل أرقامًا صحيحة موجبة في الإعدادات")
            return
        self.cfg["ntfy_topic"] = self.ntfy.get().strip()
        self.cfg["desktop_notifications"] = self.desktop.get()
        save_config(self.cfg)
        self.status.config(text="تم حفظ الإعدادات ✓")

    def toggle(self):
        if self.monitor.running:
            self.monitor.stop()
            self.toggle_btn.config(text="▶ بدء المراقبة")
            self.status.config(text="متوقفة")
        else:
            if not self.cfg["folders"]:
                messagebox.showinfo("تنبيه", "أضف فولدرًا واحدًا على الأقل")
                return
            self.monitor.start()
            self.toggle_btn.config(text="⏸ إيقاف المراقبة")
            self.status.config(text="جاري الفحص…")

    def scan_now(self):
        import threading
        threading.Thread(target=self.monitor.scan_once, daemon=True).start()
        self.status.config(text="جاري الفحص…")

    def open_log(self):
        if not LOG_PATH.exists():
            messagebox.showinfo("السجل", "لا توجد تنبيهات بعد")
            return
        if hasattr(os, "startfile"):
            os.startfile(LOG_PATH)  # Windows
        else:
            import subprocess, sys
            subprocess.Popen(["open" if sys.platform == "darwin" else "xdg-open", str(LOG_PATH)])

    def show_alert(self, alert: Alert):
        self.alerts.insert(0, f"{time.strftime('%H:%M')}  {alert.title()} — {alert.message().replace(chr(10), ' • ')}")
        self.root.bell()
        # bring the window forward briefly so the alert is noticed
        self.root.deiconify()
        self.root.attributes("-topmost", True)
        self.root.after(1500, lambda: self.root.attributes("-topmost", False))

    def refresh(self):
        with self.monitor.lock:
            states = dict(self.monitor.states)
        self.tree.delete(*self.tree.get_children())
        now = time.time()
        for path in self.cfg["folders"]:
            st = states.get(path)
            if st is None or (not st.samples and not st.error):
                row, tag = ("في الانتظار…", "", "", path), ()
            elif st.error:
                row, tag = (st.error, "", "", path), ("error",)
            else:
                g, _ = st.growth()
                alerting = now - st.last_alert < self.cfg["cooldown_minutes"] * 60
                row = ("⚠ زيادة سريعة" if alerting else "✓ طبيعي",
                       ("+" if g > 0 else "") + human_size(g), human_size(st.current), path)
                tag = ("alert",) if alerting else ()
            self.tree.insert("", "end", iid=path, values=row, tags=tag)
        if self.monitor.running:
            self.status.config(text=f"آخر فحص: {time.strftime('%H:%M:%S')}")

    def on_close(self):
        self.monitor.stop()
        self.root.destroy()


def main():
    root = tk.Tk()
    try:
        ttk.Style().theme_use("vista" if os.name == "nt" else "clam")
    except tk.TclError:
        pass
    App(root)
    root.mainloop()


if __name__ == "__main__":
    main()
