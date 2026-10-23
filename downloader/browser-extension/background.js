// Watches the network traffic of each tab and remembers responses that are
// complete videos with sound, so the popup can list their direct links.
// Sound-only files and stream playlists (m3u8/mpd) are ignored.

const MEDIA_EXT = /\.(mp4|m4v|webm|mkv|mov|avi|flv|wmv|3gp|ogv|m3u8|mpd|mp3|m4a|aac|ogg|opus|wav|flac)$/i;
const STREAM_TYPES = {
  "application/vnd.apple.mpegurl": "m3u8",
  "application/x-mpegurl": "m3u8",
  "audio/mpegurl": "m3u8",
  "audio/x-mpegurl": "m3u8",
  "application/dash+xml": "mpd",
};
// Small pieces of a stream - the playlist (m3u8/mpd) is the useful link, not these.
const SEGMENT_TYPES = new Set(["video/mp2t", "video/iso.segment", "audio/iso.segment"]);
const SEGMENT_EXT = /\.(ts|m4s|cmfv|cmfa)$/i;
// Query parameters that change between pieces of the same file.
const PIECE_PARAMS = ["range", "rn", "rbuf", "bytestart", "byteend", "_"];
const MIN_SIZE = 64 * 1024;
// YouTube formats that hold picture and sound together; every other format
// there is picture-only or sound-only.
const YT_WITH_SOUND = new Set(["18", "22", "37", "38", "43", "59", "78"]);
const MAX_PER_TAB = 100;

const tabs = new Map(); // tabId -> array of found items
const ready = chrome.storage.session.get(null).then((saved) => {
  for (const [key, items] of Object.entries(saved)) {
    if (key.startsWith("tab:")) tabs.set(Number(key.slice(4)), items);
  }
});

function header(headers, name) {
  const h = headers.find((x) => x.name.toLowerCase() === name);
  return h ? h.value : "";
}

function extOf(url) {
  try {
    const m = new URL(url).pathname.match(/\.([a-z0-9]{2,4})$/i);
    return m ? m[1].toLowerCase() : "";
  } catch {
    return "";
  }
}

function keyOf(url) {
  try {
    const u = new URL(url);
    for (const p of PIECE_PARAMS) u.searchParams.delete(p);
    return u.toString();
  } catch {
    return url;
  }
}

function classify(details) {
  const headers = details.responseHeaders || [];
  const ctype = header(headers, "content-type").split(";")[0].trim().toLowerCase();
  const ext = extOf(details.url);
  if (SEGMENT_TYPES.has(ctype) || SEGMENT_EXT.test(ext ? "." + ext : "")) return null;

  let kind = null;
  if (STREAM_TYPES[ctype] || ext === "m3u8" || ext === "mpd") kind = "stream";
  else if (ctype.startsWith("video/")) kind = "video";
  else if (ctype.startsWith("audio/")) kind = "audio";
  else if (MEDIA_EXT.test("." + ext) && /octet-stream|^$/.test(ctype)) kind = /mp3|m4a|aac|ogg|opus|wav|flac/.test(ext) ? "audio" : "video";
  if (kind !== "video") return null; // only videos with sound
  try {
    const u = new URL(details.url);
    if (u.hostname.endsWith(".googlevideo.com") && !YT_WITH_SOUND.has(u.searchParams.get("itag"))) return null;
  } catch {
    return null;
  }

  let size = null;
  const range = header(headers, "content-range").match(/\/(\d+)\s*$/);
  if (range) size = Number(range[1]);
  else if (details.statusCode === 200 && header(headers, "content-length")) size = Number(header(headers, "content-length"));
  if (size !== null && size < MIN_SIZE) return null; // icons, previews, ads

  const quality = (decodeURIComponent(details.url).match(/(?<![0-9])(2160|1440|1080|720|540|480|360|240|144)p(?![a-z0-9])/i) || [])[1];
  return {
    url: keyOf(details.url),
    kind,
    ext: ext || STREAM_TYPES[ctype] || ctype.split("/")[1] || "",
    size,
    quality: quality ? quality + "p" : "",
    time: Date.now(),
  };
}

async function save(tabId) {
  const items = tabs.get(tabId) || [];
  if (items.length) await chrome.storage.session.set({ ["tab:" + tabId]: items });
  else await chrome.storage.session.remove("tab:" + tabId);
  const n = items.length;
  chrome.action.setBadgeText({ tabId, text: n ? String(n) : "" }).catch(() => {});
  chrome.action.setBadgeBackgroundColor({ tabId, color: "#1f6feb" }).catch(() => {});
}

chrome.webRequest.onHeadersReceived.addListener(
  (details) => {
    if (details.tabId < 0 || details.statusCode >= 400) return;
    const item = classify(details);
    if (!item) return;
    ready.then(() => {
      const items = tabs.get(details.tabId) || [];
      const old = items.find((i) => i.url === item.url);
      if (old) {
        if (!old.size && item.size) old.size = item.size;
        return save(details.tabId);
      }
      items.push(item);
      if (items.length > MAX_PER_TAB) items.shift();
      tabs.set(details.tabId, items);
      return save(details.tabId);
    });
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders"]
);

// A new page in the tab: forget the previous page's videos.
chrome.webRequest.onBeforeRequest.addListener(
  (details) => {
    if (details.tabId < 0) return;
    ready.then(() => {
      tabs.delete(details.tabId);
      return save(details.tabId);
    });
  },
  { urls: ["<all_urls>"], types: ["main_frame"] }
);

chrome.tabs.onRemoved.addListener((tabId) => {
  ready.then(() => {
    tabs.delete(tabId);
    return chrome.storage.session.remove("tab:" + tabId);
  });
});

chrome.runtime.onMessage.addListener((msg, _sender, reply) => {
  if (msg.type === "list") {
    ready.then(() => reply(tabs.get(msg.tabId) || []));
    return true;
  }
  if (msg.type === "clear") {
    ready.then(() => {
      tabs.delete(msg.tabId);
      return save(msg.tabId);
    }).then(() => reply([]));
    return true;
  }
});
