import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import notify  # noqa: E402


class FakeClient:
    def __init__(self, fail=False):
        self.posts, self.fail = [], fail

    def post(self, url, json=None, timeout=None):
        if self.fail:
            raise RuntimeError("network down")
        self.posts.append(json)
        return type("R", (), {"raise_for_status": lambda self: None})()


def story(n, tier="urgent", age_h=1, score=10):
    return {"title": f"خبر {n}", "summary": "ملخص", "tier": tier, "score": score - n,
            "published": time.time() - age_h * 3600, "n_sources": 3,
            "sources": [{"url": f"https://x.test/{n}"}]}


def test_alerts_only_new_fresh_urgent_and_never_twice(tmp_path, monkeypatch):
    monkeypatch.setenv("NTFY_TOPIC", "t")
    state = tmp_path / "n.json"
    stories = [story(1), story(2, tier="important"), story(3, age_h=10), story(4)]
    c = FakeClient()
    assert notify.notify(stories, c, state) == 2
    assert [p["title"] for p in c.posts] == ["🔴 عاجل · خبر 1", "🔴 عاجل · خبر 4"]
    c2 = FakeClient()
    assert notify.notify(stories, c2, state) == 0 and c2.posts == []


def test_caps_per_run_and_per_day(tmp_path, monkeypatch):
    monkeypatch.setenv("NTFY_TOPIC", "t")
    state = tmp_path / "n.json"
    for batch in range(6):
        notify.notify([story(batch * 10 + i) for i in range(5)], FakeClient(), state)
    data = json.loads(state.read_text())
    assert len(data["sent_times"]) == notify.MAX_PER_DAY


def test_failed_send_is_retried(tmp_path, monkeypatch):
    monkeypatch.setenv("NTFY_TOPIC", "t")
    state = tmp_path / "n.json"
    assert notify.notify([story(1)], FakeClient(fail=True), state) == 0
    assert notify.notify([story(1)], FakeClient(), state) == 1


def test_disabled_without_topic(tmp_path, monkeypatch):
    monkeypatch.delenv("NTFY_TOPIC", raising=False)
    assert notify.notify([story(1)], FakeClient(), tmp_path / "n.json") == 0
