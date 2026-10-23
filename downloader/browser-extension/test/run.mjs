// Loads the extension into Chromium, plays a page whose player builds its video
// address in JavaScript, and checks what the extension caught.
// Run: NODE_PATH=$(npm root -g) xvfb-run node test/run.mjs [screenshot.png]
import http from "node:http";
import os from "node:os";
import path from "node:path";
import fs from "node:fs";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const { chromium } = createRequire(import.meta.url)("playwright");
const extDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const big = Buffer.alloc(3 * 1024 * 1024, 7);

const server = http.createServer((req, res) => {
  const url = new URL(req.url, "http://x");
  const media = (type, body) => {
    const m = /bytes=(\d+)-(\d*)/.exec(req.headers.range || "");
    if (m) {
      const start = Number(m[1]), end = Math.min(m[2] ? Number(m[2]) : body.length - 1, body.length - 1);
      res.writeHead(206, { "Content-Type": type, "Content-Range": `bytes ${start}-${end}/${body.length}`,
                           "Content-Length": end - start + 1, "Accept-Ranges": "bytes" });
      return res.end(body.subarray(start, end + 1));
    }
    res.writeHead(200, { "Content-Type": type, "Content-Length": body.length });
    res.end(body);
  };
  switch (url.pathname) {
    case "/watch":
      res.writeHead(200, { "Content-Type": "text/html" });
      return res.end(`<!doctype html><title>Demo player</title><video id=v muted></video><script>
        // Address assembled at run time - invisible to a scan of the page's HTML.
        const host = location.origin, id = ["mov", "ie"].join("");
        document.getElementById("v").src = host + "/cdn/" + id + "_1080p.mp4?token=" + Date.now();
        fetch("/live/master.m3u8");
        fetch("/live/seg_001.ts");
        fetch("/thumbs/preview.mp4");
        fetch("/audio/track.m4a");
      </script>`);
    case "/next":
      res.writeHead(200, { "Content-Type": "text/html" });
      return res.end("<title>Another page</title>nothing here");
    case "/cdn/movie_1080p.mp4": return media("video/mp4", big);
    case "/live/master.m3u8": return media("application/vnd.apple.mpegurl", Buffer.from("#EXTM3U\n"));
    case "/live/seg_001.ts": return media("video/mp2t", big);
    case "/thumbs/preview.mp4": return media("video/mp4", Buffer.alloc(10_000));
    case "/audio/track.m4a": return media("audio/mp4", big);
    default: res.writeHead(404); res.end();
  }
});
await new Promise((r) => server.listen(0, "127.0.0.1", r));
const origin = `http://127.0.0.1:${server.address().port}`;

const profile = fs.mkdtempSync(path.join(os.tmpdir(), "tdl-ext-"));
const ctx = await chromium.launchPersistentContext(profile, {
  headless: false,
  args: [`--disable-extensions-except=${extDir}`, `--load-extension=${extDir}`],
});
try {
  let [worker] = ctx.serviceWorkers();
  worker ??= await ctx.waitForEvent("serviceworker");
  const extId = worker.url().split("/")[2];

  const page = await ctx.newPage();
  await page.goto(origin + "/watch");
  await page.waitForTimeout(1500);
  const tabId = await worker.evaluate(async () => (await chrome.tabs.query({ title: "Demo player" }))[0].id);
  const items = await worker.evaluate((id) => chrome.storage.session.get("tab:" + id).then((s) => s["tab:" + id] || []), tabId);
  const caught = items.map((i) => `${i.kind} ${new URL(i.url).pathname} ${i.quality} ${i.size ?? ""}`.trim()).sort();
  console.log("caught:", caught);
  // Only the video with sound: the stream playlist, its piece, the sound-only
  // file and the tiny preview are all skipped.
  assert.deepEqual(caught, [`video /cdn/movie_1080p.mp4 1080p ${big.length}`]);
  assert.ok(items.find((i) => i.kind === "video").url.includes("token="), "keeps the full signed address");

  const popup = await ctx.newPage();
  await popup.setViewportSize({ width: 440, height: 300 });
  await popup.goto(`chrome-extension://${extId}/popup.html?tab=${tabId}`);
  await popup.waitForSelector("li");
  assert.equal(await popup.locator("li").count(), 1);
  assert.match(await popup.locator("li .name").first().textContent(), /movie_1080p\.mp4/);
  if (process.argv[2]) await popup.screenshot({ path: process.argv[2] });

  await page.goto(origin + "/next");
  await page.waitForTimeout(500);
  const after = await worker.evaluate((id) => chrome.storage.session.get("tab:" + id).then((s) => s["tab:" + id] || []), tabId);
  assert.equal(after.length, 0, "list is cleared when the tab opens another page");
  console.log("extension test passed");
} finally {
  await ctx.close();
  server.close();
}
