const list = document.getElementById("list");
const empty = document.getElementById("empty");
let tabId = null;
let items = [];

function humanSize(n) {
  if (!n) return "";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let i = 0;
  while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
  return (i ? n.toFixed(1) : n) + " " + units[i];
}

function nameOf(url) {
  try {
    const last = new URL(url).pathname.split("/").filter(Boolean).pop() || url;
    return decodeURIComponent(last);
  } catch {
    return url;
  }
}

function chip(text, cls = "") {
  const s = document.createElement("span");
  s.className = "chip " + cls;
  s.textContent = text;
  return s;
}

function button(text, onClick, primary = false) {
  const b = document.createElement("button");
  b.textContent = text;
  if (primary) b.className = "primary";
  b.addEventListener("click", onClick);
  return b;
}

async function copy(text, btn) {
  await navigator.clipboard.writeText(text);
  const old = btn.textContent;
  btn.textContent = "Copied ✓";
  setTimeout(() => (btn.textContent = old), 1200);
}

const ORDER = { video: 0, stream: 1, audio: 2 };

function render() {
  list.replaceChildren();
  empty.hidden = items.length > 0;
  const sorted = [...items].sort((a, b) =>
    (ORDER[a.kind] - ORDER[b.kind]) || (parseInt(b.quality) || 0) - (parseInt(a.quality) || 0) || (b.size || 0) - (a.size || 0));
  for (const item of sorted) {
    const li = document.createElement("li");
    const name = document.createElement("div");
    name.className = "name";
    name.textContent = nameOf(item.url);
    const url = document.createElement("div");
    url.className = "url";
    url.textContent = item.url;
    url.title = item.url;

    const row = document.createElement("div");
    row.className = "row";
    row.append(chip(item.kind === "stream" ? "stream playlist" : item.kind, item.kind === "stream" ? "stream" : ""));
    if (item.ext) row.append(chip(item.ext));
    if (item.quality) row.append(chip(item.quality));
    if (item.size) row.append(chip(humanSize(item.size)));
    const spacer = document.createElement("span");
    spacer.className = "spacer";
    row.append(spacer);
    const copyBtn = button("Copy link", () => copy(item.url, copyBtn), true);
    const dlBtn = button("Download", () => chrome.downloads.download({ url: item.url }));
    if (item.kind === "stream") {
      dlBtn.disabled = true;
      dlBtn.title = "A stream is many small pieces, not one file. Copy the link and open it in VLC or yt-dlp.";
    }
    row.append(copyBtn, dlBtn);
    li.append(name, url, row);
    list.append(li);
  }
}

async function load() {
  const param = new URLSearchParams(location.search).get("tab");
  if (param) tabId = Number(param);
  else [{ id: tabId }] = await chrome.tabs.query({ active: true, currentWindow: true });
  items = await chrome.runtime.sendMessage({ type: "list", tabId });
  render();
}

document.getElementById("clear").addEventListener("click", async () => {
  items = await chrome.runtime.sendMessage({ type: "clear", tabId });
  render();
});
const copyAll = document.getElementById("copyAll");
copyAll.addEventListener("click", () => items.length && copy(items.map((i) => i.url).join("\n"), copyAll));

load();
