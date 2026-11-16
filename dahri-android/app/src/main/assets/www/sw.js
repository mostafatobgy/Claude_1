/* Offline support for the web (iPhone) version. Not used inside the Android app, which loads files
   straight from the APK. Serves saved copies at once and refreshes them in the background, so the app
   opens without internet and picks up updates on the next launch. VERSION is stamped at deploy time. */
"use strict";
var VERSION = "__VERSION__";
var CACHE = "afham-" + VERSION;
var FILES = [
  "./", "index.html", "style.css", "data.js", "draw.js", "model3d.js", "spine3d.js", "app.js",
  "manifest.webmanifest", "lib/three.min.js",
  "fonts/tajawal-arabic-400-normal.woff2", "fonts/tajawal-arabic-500-normal.woff2", "fonts/tajawal-arabic-700-normal.woff2",
  "fonts/tajawal-latin-400-normal.woff2", "fonts/tajawal-latin-700-normal.woff2",
  "icons/icon-180.png", "icons/icon-192.png", "icons/icon-512.png"
];
self.addEventListener("install", function (e) {
  e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(FILES); }).then(function () { return self.skipWaiting(); }));
});
self.addEventListener("activate", function (e) {
  e.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) { return k.indexOf("afham-") === 0 && k !== CACHE; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});
self.addEventListener("fetch", function (e) {
  var req = e.request;
  if (req.method !== "GET" || new URL(req.url).origin !== location.origin) return;   // videos/thumbnails: straight to the network
  e.respondWith(caches.open(CACHE).then(function (c) {
    return c.match(req, { ignoreSearch: true }).then(function (hit) {
      var net = fetch(req).then(function (res) { if (res.ok) c.put(req, res.clone()); return res; }).catch(function () { return hit; });
      return hit || net;
    });
  }));
});
