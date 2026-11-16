/* Interactive 3D model of two lumbar vertebrae, the disc between them, the nerve sac and the nerve roots.
   Drag with one finger to turn it, pinch to zoom. Built with three.js (bundled in lib/), original geometry.
   Uses the same disc shape and nerve-contact logic as the 2D cross-section in draw.js. */
"use strict";

function webglOK() {
  try { var c = document.createElement("canvas"); return !!(window.WebGLRenderingContext && (c.getContext("webgl") || c.getContext("experimental-webgl"))); }
  catch (e) { return false; }
}

/* Finger controls for a 3D view: one finger turns it, two fingers zoom, and a button resets it.
   Reads the fingers the browser reports as on the screen right now (e.touches) on every event,
   so a lift the browser never reported cannot leave a "stuck" finger behind. */
function attachOrbit(wrap, canvas, view, minR, maxR, maxEl, onTouch) {
  var home = { az: view.az, el: view.el, r: view.r }, last = null;
  function snap(t) {
    if (t.length === 1) return { n: 1, x: t[0].clientX, y: t[0].clientY };
    if (t.length >= 2) return { n: 2, d: Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY) || 1 };
    return null;
  }
  function turn(dx, dy) { view.az -= dx * 0.012; view.el = Math.max(-maxEl, Math.min(maxEl, view.el + dy * 0.012)); }
  function zoom(k) { view.r = Math.max(minR, Math.min(maxR, view.r * k)); }
  canvas.style.touchAction = "none";
  canvas.addEventListener("touchstart", function (e) { e.preventDefault(); onTouch(); last = snap(e.touches); }, { passive: false });
  canvas.addEventListener("touchmove", function (e) {
    e.preventDefault();
    var cur = snap(e.touches);
    if (cur && last && cur.n === last.n) {
      if (cur.n === 1) turn(cur.x - last.x, cur.y - last.y); else zoom(last.d / cur.d);
    }
    last = cur;
  }, { passive: false });
  function end(e) { last = snap(e.touches); }
  canvas.addEventListener("touchend", end); canvas.addEventListener("touchcancel", end);
  // mouse, for testing on a computer
  var mouse = null;
  canvas.addEventListener("mousedown", function (e) { onTouch(); mouse = { x: e.clientX, y: e.clientY }; });
  window.addEventListener("mousemove", function (e) { if (mouse) { turn(e.clientX - mouse.x, e.clientY - mouse.y); mouse = { x: e.clientX, y: e.clientY }; } });
  window.addEventListener("mouseup", function () { mouse = null; });
  canvas.addEventListener("wheel", function (e) { e.preventDefault(); zoom(e.deltaY > 0 ? 1.08 : 0.93); }, { passive: false });
  var reset = document.createElement("button");
  reset.className = "m3d-reset"; reset.setAttribute("aria-label", "إرجاع المجسم لوضعه الأول"); reset.textContent = "↺";
  reset.onclick = function () { view.az = home.az; view.el = home.el; view.r = home.r; };
  wrap.appendChild(reset);
}

/* 2D cross-section coordinates (draw.js) → 3D: x right, z toward the back, y up. */
function to3(x, y, h) { return new THREE.Vector3((x - 160) / 10, h || 0, (y - 96) / 10); }

function model3D(opts) {
  opts = opts || {};
  if (!window.THREE || !webglOK()) return null;
  var wrap = document.createElement("div");
  wrap.className = "m3d";
  var canvas = document.createElement("canvas");
  wrap.appendChild(canvas);
  var hint = document.createElement("div");
  hint.className = "m3d-hint";
  hint.textContent = "اسحب بإصبعك لتدوير المجسم، وباصبعين للتكبير";
  wrap.appendChild(hint);

  var renderer = new THREE.WebGLRenderer({ canvas: canvas, antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  var scene = new THREE.Scene();
  var camera = new THREE.PerspectiveCamera(35, 1, 0.5, 200);
  scene.add(new THREE.HemisphereLight(0xffffff, 0x6b7a8a, 0.55));
  var sun = new THREE.DirectionalLight(0xffffff, 0.8); sun.position.set(12, 30, 25); scene.add(sun);
  var fill = new THREE.DirectionalLight(0xffffff, 0.35); fill.position.set(-20, -5, -20); scene.add(fill);

  var M = {
    bone: new THREE.MeshStandardMaterial({ color: 0xE2CFA6, roughness: 0.8 }),
    // the upper vertebra is see-through so the disc and the slipped part stay visible
    ghost: new THREE.MeshStandardMaterial({ color: 0xE2CFA6, roughness: 0.8, transparent: true, opacity: 0.2, depthWrite: false }),
    disc: new THREE.MeshStandardMaterial({ color: 0xC99A5B, roughness: 0.6, transparent: true, opacity: 0.55, depthWrite: false }),
    nucleus: new THREE.MeshStandardMaterial({ color: 0xF06292, roughness: 0.4 }),
    hernia: new THREE.MeshStandardMaterial({ color: 0xE91E63, roughness: 0.35, emissive: 0x4a0016 }),
    sac: new THREE.MeshStandardMaterial({ color: 0x7FB8EE, roughness: 0.4, transparent: true, opacity: 0.38, depthWrite: false }),
    nerve: function () { return new THREE.MeshStandardMaterial({ color: 0xF2B705, roughness: 0.5, emissive: 0x000000 }); }
  };

  // ---- fixed anatomy ----
  function outlinePts(s, scale) {
    var pts = [], n = 96;
    for (var i = 0; i < n; i++) {
      var th = -Math.PI + i * 2 * Math.PI / n, r = outlineR(th, s) * (scale || 1);
      pts.push(new THREE.Vector2(r * Math.cos(th) / 10, r * Math.sin(th) / 10));
    }
    return pts;
  }
  function slab(pts, h, yTop, bevel) {
    var g = new THREE.ExtrudeGeometry(new THREE.Shape(pts), { depth: h - 2 * bevel, bevelEnabled: bevel > 0, bevelThickness: bevel, bevelSize: bevel, bevelSegments: 3, curveSegments: 4 });
    g.rotateX(Math.PI / 2);           // shape plane → horizontal; extrusion goes downward
    g.translate(0, yTop - bevel, 0);
    return g;
  }
  var flat = { theta: 1.18, bulge: 0, prot: 0, len: 0, head: 0, tear: 0, free: 0 };
  var bodyPts = outlinePts(flat, 0.97);
  scene.add(new THREE.Mesh(slab(bodyPts, 2.5, 3.05, 0.25), M.ghost));   // upper vertebral body
  scene.add(new THREE.Mesh(slab(bodyPts, 2.5, -0.55, 0.25), M.bone));   // lower vertebral body

  function backArch(yc, mat) {      // pedicles, laminae, spinous and transverse processes
    var ring = [[104, 146], [102, 176], [112, 196], [128, 214], [160, 234], [192, 214], [208, 196], [218, 176], [216, 146]]
      .map(function (p) { return to3(p[0], p[1], 0); });
    var tube = new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(ring), 40, 0.55, 10, false), mat);
    tube.scale.y = 2.1; tube.position.y = yc; scene.add(tube);
    var sp = new THREE.Mesh(new THREE.BoxGeometry(1, 1.6, 4.2), mat);
    sp.position.set(0, yc - 0.7, 16.3); sp.rotation.x = -0.45; scene.add(sp);
    [-1, 1].forEach(function (side) {
      var tp = new THREE.Mesh(new THREE.BoxGeometry(5, 0.9, 1.2), mat);
      tp.position.set(side * 7.4, yc + 0.2, 7.6); tp.rotation.y = side * -0.25; scene.add(tp);
    });
  }
  backArch(1.85, M.ghost); backArch(-1.85, M.bone);

  var sacGeo = new THREE.CylinderGeometry(1, 1, 9, 32, 1, true);
  var sac = new THREE.Mesh(sacGeo, M.sac);
  sac.scale.set(4.1, 1, 2.3); sac.position.set(0, 0, 10); scene.add(sac);

  function nerve(points) {
    var mat = M.nerve();
    var mesh = new THREE.Mesh(new THREE.TubeGeometry(new THREE.CatmullRomCurve3(points), 48, 0.34, 10, false), mat);
    scene.add(mesh);
    return mat;
  }
  // roots travelling down (to the next level) and roots exiting at this level, right (index 0) and left
  var travMats = [1, -1].map(function (side) {
    return nerve([new THREE.Vector3(side * 3, 4.4, 8.6), new THREE.Vector3(side * 3, 0, 8.6), new THREE.Vector3(side * 3.2, -3.2, 8.4), new THREE.Vector3(side * 6.5, -4.4, 7)]);
  });
  var exitMats = [1, -1].map(function (side) {
    var pts = [[198, 196, 2.4], [210, 190, 1.2], [236, 176, 0.8], [262, 160, 0.5], [292, 140, 0.1], [314, 128, -0.4]]
      .map(function (p) { return new THREE.Vector3(side * (p[0] - 160) / 10, p[2], (p[1] - 96) / 10); });
    pts.unshift(new THREE.Vector3(side * 2.6, 4.4, 9.6));
    return nerve(pts);
  });

  // ---- disc, rebuilt whenever the shape changes ----
  var dyn = new THREE.Group(); scene.add(dyn);
  var cur = Object.assign({}, flat), hits = null;
  function clearDyn() {
    while (dyn.children.length) { var c = dyn.children.pop(); c.geometry.dispose(); }
  }
  function build(s) {
    clearDyn();
    dyn.add(new THREE.Mesh(slab(outlinePts(s, 1), 1.1, 0.55, 0.12), M.disc));
    var nuc = new THREE.Mesh(new THREE.SphereGeometry(1, 24, 16), M.nucleus);
    nuc.scale.set(AX.nrx / 10, 0.38, AX.nry / 10); dyn.add(nuc);
    var H = herniaHits(s), G = H.G;
    hits = H;
    if (s.len > 0.02) {                       // tongue of nucleus pushing out through the annulus
      var a = G.P(G.rn - 4), b = G.P(G.tip);
      var A = to3(a.x, a.y, 0), B = to3(b.x, b.y, 0), L = A.distanceTo(B);
      var tongue = new THREE.Mesh(new THREE.CylinderGeometry(0.42, 0.62, L, 16), M.nucleus);
      tongue.position.copy(A).add(B).multiplyScalar(0.5);
      tongue.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), B.clone().sub(A).normalize());
      tongue.scale.set(1, 1, 0.7);
      dyn.add(tongue);
    }
    if (G.headR > 0.5) {                      // herniated material; a free fragment slides down the canal
      var hc = G.headC, head = new THREE.Mesh(new THREE.SphereGeometry(1, 24, 16), M.hernia);
      head.scale.set(G.headR / 10, 0.45 + 0.35 * s.free, G.headR / 10 * 0.85);
      head.position.copy(to3(hc.x, hc.y, -1.9 * s.free));
      dyn.add(head);
    }
    paintNerves(0);
  }
  function paintNerves(t) {
    var glow = 0.35 + 0.25 * Math.sin(t / 180);
    function paint(mat, on) {
      mat.color.setHex(on ? 0xE53935 : 0xF2B705);
      mat.emissive.setHex(on ? 0x550000 : 0x000000);
      mat.emissiveIntensity = on ? glow * 2 : 0;
    }
    travMats.forEach(function (m, i) { paint(m, hits.trav[i]); });
    exitMats.forEach(function (m, i) { paint(m, hits.exit[i]); });
    M.sac.color.setHex(hits.sac ? 0xF48FB1 : 0x7FB8EE);
  }
  build(cur);

  // ---- camera: drag to orbit, pinch to zoom ----
  var view = { az: 0.6, el: 0.85, r: 50 }, target = new THREE.Vector3(1, -0.5, 5), touched = false;
  function place() {
    camera.position.set(target.x + view.r * Math.sin(view.az) * Math.cos(view.el), target.y + view.r * Math.sin(view.el),
      target.z + view.r * Math.cos(view.az) * Math.cos(view.el));
    camera.lookAt(target);
  }
  attachOrbit(wrap, canvas, view, 18, 80, 1.3, function () { touched = true; hint.style.opacity = 0; });

  // ---- shape animation ----
  var tween = null;
  var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  function go(target2) { tween = { from: Object.assign({}, cur), to: target2, t0: null, dur: reduce ? 1 : 900 }; }

  var lastW = 0, lastH = 0;
  function size() {   // full screen: fill the box CSS gives us; otherwise keep a fixed shape
    var w = wrap.clientWidth || 320, h = wrap.closest(".fs") ? wrap.clientHeight || w : Math.round(w * 0.8);
    if (w !== lastW || h !== lastH) { renderer.setSize(w, h, false); canvas.style.width = w + "px"; canvas.style.height = h + "px"; camera.aspect = w / h; camera.updateProjectionMatrix(); lastW = w; lastH = h; }
  }
  var started = performance.now();
  function frame(ts) {
    if (!wrap.isConnected && performance.now() - started > 2000) {   // page left: free GPU memory
      clearDyn(); renderer.dispose(); return;
    }
    requestAnimationFrame(frame);
    if (!wrap.isConnected) return;
    size();
    if (tween) {
      if (tween.t0 === null) tween.t0 = ts;
      var k = Math.min((ts - tween.t0) / tween.dur, 1), e = k < .5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
      for (var key in tween.to) cur[key] = tween.from[key] + (tween.to[key] - tween.from[key]) * e;
      build(cur);
      if (k >= 1) tween = null;
    }
    if (!touched && !reduce) view.az = 0.6 + 0.45 * Math.sin((ts - started) / 3000);
    paintNerves(ts);
    place();
    renderer.render(scene, camera);
  }
  requestAnimationFrame(frame);
  return { el: wrap, go: go };
}

/* Legend shown under the 3D model. */
var LEGEND_3D = '<div class="legend">' +
  [["#E2CFA6", "الفقرات (العليا شفافة)"], ["#C99A5B", "القرص (الحلقة الليفية)"], ["#F06292", "النواة"], ["#E91E63", "الجزء المنزلق"],
   ["#7FB8EE", "الكيس العصبي"], ["#F2B705", "جذور الأعصاب"], ["#E53935", "عصب مضغوط"]]
    .map(function (x) { return '<span><i style="background:' + x[0] + '"></i>' + x[1] + '</span>'; }).join("") + '</div>';
