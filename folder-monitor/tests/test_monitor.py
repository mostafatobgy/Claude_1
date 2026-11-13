import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import monitor  # noqa: E402
from monitor import FolderState, Monitor, Sample, check_growth, human_size, scan_folder  # noqa: E402

MB = 1024 * 1024
CFG = dict(monitor.DEFAULT_CONFIG, threshold_mb=100, window_minutes=10, cooldown_minutes=15)


def test_scan_folder_counts_nested_files(tmp_path):
    (tmp_path / "a").mkdir()
    (tmp_path / "a" / "b").mkdir()
    (tmp_path / "a" / "b" / "f.bin").write_bytes(b"x" * 300)
    (tmp_path / "top.txt").write_bytes(b"y" * 50)
    total, children = scan_folder(str(tmp_path))
    assert total == 350
    assert children == {"a": 300, "top.txt": 50}


def test_scan_missing_folder_returns_zero(tmp_path):
    assert scan_folder(str(tmp_path / "nope")) == (0, {})


def test_alert_on_fast_growth_names_top_grower():
    st = FolderState("/data")
    st.add(Sample(0, 1000 * MB, {"logs": 10 * MB, "db": 990 * MB}), 600)
    st.add(Sample(60, 1200 * MB, {"logs": 210 * MB, "db": 990 * MB}), 600)
    a = check_growth(st, CFG, 60)
    assert a and a.growth == 200 * MB
    assert a.top_growers == [("logs", 200 * MB)]
    assert "logs" in a.message()


def test_no_alert_below_threshold():
    st = FolderState("/data")
    st.add(Sample(0, 1000 * MB, {}), 600)
    st.add(Sample(60, 1050 * MB, {}), 600)
    assert check_growth(st, CFG, 60) is None


def test_slow_growth_outside_window_is_ignored():
    st = FolderState("/data")
    # +60 MB every 10 minutes: never 100 MB inside a 10-minute window
    for i in range(10):
        t = i * 600
        st.add(Sample(t, (1000 + 60 * i) * MB, {}), 600)
        assert check_growth(st, CFG, t) is None
    assert len(st.samples) <= 3  # old samples are trimmed


def test_cooldown_prevents_repeat_alerts():
    st = FolderState("/data")
    st.add(Sample(0, 0, {}), 600)
    st.add(Sample(60, 200 * MB, {}), 600)
    assert check_growth(st, CFG, 60)
    st.add(Sample(120, 400 * MB, {}), 600)
    assert check_growth(st, CFG, 120) is None
    st.add(Sample(60 + 15 * 60, 900 * MB, {}), 600)
    assert check_growth(st, CFG, 60 + 15 * 60)


def test_percent_threshold():
    cfg = dict(CFG, threshold_mb=0, threshold_percent=50)
    st = FolderState("/small")
    st.add(Sample(0, 10 * MB, {}), 600)
    st.add(Sample(60, 16 * MB, {}), 600)
    assert check_growth(st, cfg, 60).percent == 60


def test_monitor_end_to_end(tmp_path, monkeypatch):
    sent = []
    monkeypatch.setattr(monitor, "LOG_PATH", tmp_path / "alerts.log")
    monkeypatch.setattr(monitor, "desktop_notify", lambda t, m: sent.append(t))
    watched = tmp_path / "watched"
    watched.mkdir()
    cfg = dict(CFG, folders=[str(watched)], threshold_mb=1)
    mon = Monitor(cfg)
    assert mon.scan_once() == []
    (watched / "big.bin").write_bytes(b"0" * (2 * MB))
    alerts = mon.scan_once()
    assert len(alerts) == 1 and sent
    assert "big.bin" in (tmp_path / "alerts.log").read_text(encoding="utf-8")


def test_human_size():
    assert human_size(512) == "512 B"
    assert human_size(1536) == "1.5 KB"
    assert human_size(-3 * MB) == "-3.0 MB"
