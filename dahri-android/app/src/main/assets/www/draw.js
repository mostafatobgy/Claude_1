/* Original SVG illustrations, drawn in code so they stay sharp and work offline. */
"use strict";

var SVGNS = "http://www.w3.org/2000/svg";
function el(name, attrs, parent) {
  var e = document.createElementNS(SVGNS, name);
  for (var k in attrs) e.setAttribute(k, attrs[k]);
  if (parent) parent.appendChild(e);
  return e;
}
function txt(parent, x, y, s, attrs) {
  var a = { x: x, y: y, "font-size": 11, fill: "#172B3A", "text-anchor": "middle" };
  for (var k in attrs || {}) a[k] = attrs[k];
  var t = el("text", a, parent);
  t.textContent = s;
  return t;
}
function svgRoot(w, h, label) {
  var s = el("svg", { viewBox: "0 0 " + w + " " + h, role: "img", "aria-label": label || "" });
  return s;
}

/* ---------- Side view of the whole spine ---------- */
var SPINE_LIST = (function () {
  var list = [], i;
  for (i = 1; i <= 7; i++) list.push({ code: "C" + i, r: "C", h: 15, w: 32, gap: 7 });
  for (i = 1; i <= 12; i++) list.push({ code: "T" + i, r: "T", h: 19, w: 38, gap: 7 });
  for (i = 1; i <= 5; i++) list.push({ code: "L" + i, r: "L", h: 25, w: 50, gap: 10 });
  return list;
})();

/* Draws the spine. opts.onPick(kind, code) — kind is "v" (vertebra), "d" (disc) or "s" (sacrum).
   opts.highlight = disc label like "L4-L5" to mark in pink. */
function drawSpine(opts) {
  opts = opts || {};
  var W = 300, top = 20, svg = svgRoot(W, 760, "رسم جانبي للعمود الفقري");
  var defs = el("defs", {}, svg);
  var g = el("g", {}, svg);
  var y = top, total = 0, items = [];
  SPINE_LIST.forEach(function (v) { total += v.h + v.gap; });
  // S-shaped curve: neck and lower back curve toward the front (right side of the drawing).
  function cx(yy) {
    var t = (yy - top) / (total + 90);
    return 150 + 16 * Math.sin(Math.min(t / 0.19, 1) * Math.PI) * (t < 0.19 ? 1 : 0)
      - 20 * (t >= 0.19 && t < 0.6 ? Math.sin((t - 0.19) / 0.41 * Math.PI) : 0)
      + 18 * (t >= 0.6 && t < 0.86 ? Math.sin((t - 0.6) / 0.26 * Math.PI) : 0)
      - 26 * (t >= 0.86 ? (t - 0.86) / 0.14 : 0);
  }
  function slope(yy) { return Math.atan2(cx(yy + 1) - cx(yy - 1), 2) * 180 / Math.PI; }

  txt(svg, 270, 14, "الأمام", { fill: "#5B6B7F", "font-size": 11 });
  txt(svg, 30, 14, "الخلف", { fill: "#5B6B7F", "font-size": 11 });

  SPINE_LIST.forEach(function (v, idx) {
    var reg = REGIONS[v.r], mid = y + v.h / 2, x = cx(mid), rot = -slope(mid);
    var grp = el("g", { transform: "translate(" + x + "," + mid + ") rotate(" + rot + ")", "class": "hit", "data-code": v.code }, g);
    // spinous process pointing to the back (left)
    el("rect", { x: -v.w / 2 - 16, y: -3, width: 20, height: 6, rx: 3, fill: reg.fill, stroke: reg.stroke, "stroke-width": 1 }, grp);
    var body = el("rect", { x: -v.w / 2, y: -v.h / 2, width: v.w, height: v.h, rx: 5, fill: reg.fill, stroke: reg.stroke, "stroke-width": 1.4 }, grp);
    txt(grp, 0, 4, v.code, { "font-size": v.h > 18 ? 11 : 9, "font-weight": 700, fill: reg.stroke });
    grp.addEventListener("click", function () { opts.onPick && opts.onPick("v", v.code); });
    items.push({ code: v.code, body: body });
    y += v.h;
    // disc below this vertebra (none between C1 and C2)
    var next = SPINE_LIST[idx + 1], lower = next ? next.code : "S1";
    var label = v.code + "-" + lower;
    var dy = y + v.gap / 2, dx = cx(dy), drot = -slope(dy);
    if (v.code !== "C1") {
      var dg = el("g", { transform: "translate(" + dx + "," + dy + ") rotate(" + drot + ")", "class": "hit" }, g);
      el("rect", { x: -v.w / 2 - 4, y: -8, width: v.w + 8, height: 16, fill: "transparent" }, dg); // bigger tap target
      var hl = opts.highlight === label;
      var dr = el("rect", { x: -v.w / 2 + 2, y: -v.gap / 2 + .5, width: v.w - 4, height: v.gap - 1, rx: 2,
        fill: hl ? "#EC407A" : "#7FD3C6", stroke: hl ? "#C2185B" : "none" }, dg);
      if (hl) {
        dr.setAttribute("class", "pulse");
        el("path", { d: "M" + (v.w / 2 + 4) + ",0 h22", stroke: "#C2185B", "stroke-width": 2 }, dg);
        txt(dg, v.w / 2 + 50, 4, label, { "font-size": 12, "font-weight": 700, fill: "#C2185B" });
      }
      dg.addEventListener("click", function () { opts.onPick && opts.onPick("d", label); });
    }
    y += v.gap;
  });

  // sacrum + coccyx
  var sy = y + 4, sx = cx(sy + 30);
  var sg = el("g", { "class": "hit", transform: "translate(" + sx + "," + sy + ") rotate(-28)" }, g);
  el("path", { d: "M-30,0 H30 Q26,40 8,78 Q0,86 -8,78 Q-26,40 -30,0 Z", fill: REGIONS.S.fill, stroke: REGIONS.S.stroke, "stroke-width": 1.4 }, sg);
  for (var k = 1; k < 5; k++) el("path", { d: "M" + (-26 + k * 4) + "," + k * 15 + " H" + (26 - k * 4), stroke: REGIONS.S.stroke, "stroke-width": .8, opacity: .5 }, sg);
  txt(sg, 0, 34, "العجز", { "font-size": 11, "font-weight": 700, fill: REGIONS.S.stroke });
  el("path", { d: "M-6,84 Q0,104 6,84", fill: REGIONS.S.fill, stroke: REGIONS.S.stroke, "stroke-width": 1.2 }, sg);
  sg.addEventListener("click", function () { opts.onPick && opts.onPick("s", "S"); });

  // region brackets on the right
  var spans = { C: [], T: [], L: [] };
  y = top;
  SPINE_LIST.forEach(function (v) { spans[v.r].push(y, y + v.h); y += v.h + v.gap; });
  ["C", "T", "L"].forEach(function (r) {
    var a = Math.min.apply(null, spans[r]), b = Math.max.apply(null, spans[r]), reg = REGIONS[r];
    el("path", { d: "M262," + a + " h6 V" + b + " h-6", fill: "none", stroke: reg.stroke, "stroke-width": 1.5 }, svg);
    var t = txt(svg, 284, (a + b) / 2, reg.short, { "font-size": 12, "font-weight": 700, fill: reg.stroke,
      transform: "rotate(90 284 " + (a + b) / 2 + ")" });
  });
  svg.setAttribute("viewBox", "0 0 " + W + " " + (sy + 120));
  return svg;
}

/* ---------- Cross-section (axial) view of a disc, seen from above ---------- */
var AX = { cx: 160, cy: 96, rx: 112, ry: 70, nrx: 46, nry: 27 };
var ROOT_TRAV = [{ x: 190, y: 182 }, { x: 130, y: 182 }];         // roots travelling down (right, left)
var ROOT_EXIT_R = [[210, 190], [236, 176], [262, 160], [292, 140], [314, 128]]; // exiting root, right side
var ROOT_EXIT_L = ROOT_EXIT_R.map(function (p) { return [320 - p[0], p[1]]; });

function angDiff(a, b) { var d = a - b; while (d > Math.PI) d -= 2 * Math.PI; while (d < -Math.PI) d += 2 * Math.PI; return d; }
function outlineR(th, s) {
  var c = Math.cos(th), sn = Math.sin(th);
  var r = 1 / Math.sqrt(c * c / (AX.rx * AX.rx) + sn * sn / (AX.ry * AX.ry));
  r -= 12 * Math.exp(-Math.pow(angDiff(th, Math.PI / 2) / 0.45, 2));        // back of the disc is slightly concave
  r += s.bulge * 9 * (0.5 + 0.5 * sn);                                       // broad bulge
  r += s.prot * 16 * Math.exp(-Math.pow(angDiff(th, s.theta) / 0.22, 2));     // focal bump
  return r;
}
function outlinePath(s, scale) {
  var d = "", n = 120;
  for (var i = 0; i <= n; i++) {
    var th = -Math.PI + i * 2 * Math.PI / n, r = outlineR(th, s) * (scale || 1);
    d += (i ? "L" : "M") + (AX.cx + r * Math.cos(th)).toFixed(1) + "," + (AX.cy + r * Math.sin(th)).toFixed(1);
  }
  return d + "Z";
}
function nucleusR(th) {
  var c = Math.cos(th), sn = Math.sin(th);
  return 1 / Math.sqrt(c * c / (AX.nrx * AX.nrx) + sn * sn / (AX.nry * AX.nry));
}
/* Where the herniated material is, for drawing and for checking which nerve it touches. */
function herniaGeom(s) {
  var u = { x: Math.cos(s.theta), y: Math.sin(s.theta) };
  var rn = nucleusR(s.theta), ro = outlineR(s.theta, s);
  var tip = rn + s.len * (ro - rn - 3);
  var headR = 13 * s.head;
  var headD = ro + headR * 0.55 + s.free * 8;
  var P = function (d, side) { return { x: AX.cx + d * u.x - (side || 0) * u.y, y: AX.cy + d * u.y + (side || 0) * u.x }; };
  return { u: u, rn: rn, ro: ro, tip: tip, headR: headR, headD: headD, P: P,
    bumpTip: P(ro), headC: P(headD) };
}

/* Draw into (or redraw) a <g>. s = {theta, bulge, prot, len, head, tear, free}. Returns which nerves are pressed. */
function drawAxial(g, s, showLabels) {
  while (g.firstChild) g.removeChild(g.firstChild);
  var G = herniaGeom(s);

  // which structures does the disc material reach?
  var probes = [G.bumpTip];
  if (s.head > 0.05) probes.push(G.headC);
  function reach(px, py, rad) {
    return probes.some(function (p, i) {
      var pr = i === 1 ? G.headR : 2;
      return Math.hypot(p.x - px, p.y - py) < pr + rad;
    });
  }
  var hitTrav = ROOT_TRAV.map(function (r) { return reach(r.x, r.y, 8); });
  function segDist(p, a, b) {
    var dx = b[0] - a[0], dy = b[1] - a[1], t = ((p.x - a[0]) * dx + (p.y - a[1]) * dy) / (dx * dx + dy * dy);
    t = Math.max(0, Math.min(1, t));
    return Math.hypot(p.x - a[0] - t * dx, p.y - a[1] - t * dy);
  }
  function hitPath(pts) {
    return probes.some(function (p, i) {
      var pr = i === 1 ? G.headR : 2;
      for (var k = 1; k < pts.length; k++) if (segDist(p, pts[k - 1], pts[k]) < pr + 4) return true;
      return false;
    });
  }
  var hitExit = [hitPath(ROOT_EXIT_R), hitPath(ROOT_EXIT_L)];
  var hitSac = reach(160, 174, 4) || (s.theta > 1.4 && s.theta < 1.75 && G.headR > 5 && G.headC.y + G.headR > 176);

  // bony ring behind the disc (pedicles, laminae, spinous process)
  el("path", { d: "M104,150 C100,178 112,196 126,214 L160,236 L194,214 C208,196 220,178 216,150", fill: "none",
    stroke: "#C9D3DC", "stroke-width": 9, "stroke-linecap": "round", "stroke-linejoin": "round" }, g);
  el("path", { d: "M160,236 V262", stroke: "#C9D3DC", "stroke-width": 10, "stroke-linecap": "round" }, g);

  // nerve sac and roots
  el("ellipse", { cx: 160, cy: 196, rx: 42, ry: 24, fill: hitSac ? "#F8C9D4" : "#DCEBFA", stroke: hitSac ? "#C62828" : "#7AA7D6", "stroke-width": 1.5 }, g);
  [ROOT_EXIT_R, ROOT_EXIT_L].forEach(function (pts, i) {
    var d = "M" + pts.map(function (p) { return p.join(","); }).join(" L");
    el("path", { d: d, fill: "none", stroke: hitExit[i] ? "#C62828" : "#E0A800", "stroke-width": 7, "stroke-linecap": "round", "stroke-linejoin": "round",
      "class": hitExit[i] ? "pulse" : "" }, g);
  });
  ROOT_TRAV.forEach(function (r, i) {
    el("circle", { cx: r.x, cy: r.y, r: 8, fill: hitTrav[i] ? "#C62828" : "#E0A800", stroke: "#fff", "stroke-width": 1.5, "class": hitTrav[i] ? "pulse" : "" }, g);
  });

  // disc: annulus with its rings, then the gel nucleus
  el("path", { d: outlinePath(s), fill: "#F3E3C8", stroke: "#B8925A", "stroke-width": 2 }, g);
  [0.88, 0.76, 0.64].forEach(function (k) {
    el("path", { d: outlinePath(s, k), fill: "none", stroke: "#D2B488", "stroke-width": 1 }, g);
  });
  if (s.tear > 0.02) {   // the crack the nucleus pushes through
    var a = G.P(G.rn - 2, 0), b = G.P(G.rn + s.tear * (G.ro - G.rn), 0);
    el("path", { d: "M" + a.x + "," + a.y + " L" + b.x + "," + b.y, stroke: "#8D6A3A", "stroke-width": 1.2, "stroke-dasharray": "3 2" }, g);
  }
  el("ellipse", { cx: AX.cx, cy: AX.cy, rx: AX.nrx, ry: AX.nry, fill: "#F48FB1", opacity: .9 }, g);
  if (s.len > 0.02) {    // tongue of nucleus pushing out
    var w0 = 9, w1 = 5;
    var p1 = G.P(G.rn - 6, w0), p2 = G.P(G.tip, w1), p3 = G.P(G.tip, -w1), p4 = G.P(G.rn - 6, -w0);
    el("path", { d: "M" + p1.x + "," + p1.y + " L" + p2.x + "," + p2.y + " L" + p3.x + "," + p3.y + " L" + p4.x + "," + p4.y + "Z", fill: "#F48FB1", opacity: .95 }, g);
  }
  if (G.headR > 0.5) {
    var hc = G.headC;
    el("ellipse", { cx: hc.x, cy: hc.y, rx: G.headR, ry: G.headR * 0.82, fill: "#EC407A", stroke: "#C2185B", "stroke-width": 1,
      transform: "rotate(" + (s.theta * 180 / Math.PI + 90) + " " + hc.x + " " + hc.y + ")" }, g);
  }

  if (showLabels) {
    var L = function (x, y, s2, anchor, col) { return txt(g, x, y, s2, { "font-size": 11, "text-anchor": anchor || "middle", fill: col || "#172B3A" }); };
    L(160, 16, "الأمام (جهة البطن)", "middle", "#5B6B7F");
    L(160, 99, "النواة", "middle", "#7B1F45");
    L(160, 52, "الحلقة الليفية", "middle", "#7A5A2E");
    L(160, 200, "الكيس العصبي", "middle", "#2F5E8F");
    L(318, 116, "جذر خارج", "end", "#8A6500");
    L(116, 214, "جذر نازل", "end", "#8A6500");
    L(160, 276, "الخلف (جهة الظهر)", "middle", "#5B6B7F");
  }
  return { trav: hitTrav[0], exit: hitExit[0], sac: hitSac };
}

/* Animated axial view. Returns {svg, go(params, theta)}. */
function axialWidget(showLabels) {
  var svg = svgRoot(320, 284, "مقطع عرضي للقرص من أعلى");
  var g = el("g", {}, svg);
  var cur = { theta: 1.18, bulge: 0, prot: 0, len: 0, head: 0, tear: 0, free: 0 };
  var raf = null, lastHits = drawAxial(g, cur, showLabels), onHits = null;
  var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  function go(target, done) {
    var from = Object.assign({}, cur), t0 = null, dur = reduce ? 1 : 900;
    if (raf) cancelAnimationFrame(raf);
    function step(ts) {
      if (t0 === null) t0 = ts;
      var k = Math.min((ts - t0) / dur, 1), e = k < .5 ? 2 * k * k : 1 - Math.pow(-2 * k + 2, 2) / 2;
      for (var key in target) cur[key] = from[key] + (target[key] - from[key]) * e;
      lastHits = drawAxial(g, cur, showLabels);
      if (k < 1) raf = requestAnimationFrame(step); else { raf = null; done && done(lastHits); }
    }
    raf = requestAnimationFrame(step);
  }
  return { svg: svg, go: go, hits: function () { return lastHits; } };
}

/* ---------- Side view of two vertebrae and a disc (anatomy page) ---------- */
function drawMotionSegment() {
  var svg = svgRoot(320, 230, "رسم جانبي لفقرتين وبينهما القرص");
  var bone = { fill: "#FFF4E0", stroke: "#B26A00", "stroke-width": 1.5 };
  el("rect", Object.assign({ x: 150, y: 20, width: 120, height: 70, rx: 10 }, bone), svg);
  el("rect", Object.assign({ x: 150, y: 128, width: 120, height: 70, rx: 10 }, bone), svg);
  // posterior elements (left = back)
  el("path", Object.assign({ d: "M150,40 L108,46 L60,40 L54,58 L108,66 L150,70 Z" }, bone), svg);
  el("path", Object.assign({ d: "M150,148 L108,154 L60,148 L54,166 L108,174 L150,178 Z" }, bone), svg);
  // disc
  el("rect", { x: 152, y: 92, width: 116, height: 34, rx: 8, fill: "#F3E3C8", stroke: "#B8925A", "stroke-width": 1.5 }, svg);
  el("ellipse", { cx: 212, cy: 109, rx: 30, ry: 10, fill: "#F48FB1" }, svg);
  // canal with nerves, and a root leaving through the foramen
  el("rect", { x: 120, y: 10, width: 24, height: 210, rx: 10, fill: "#DCEBFA", stroke: "#7AA7D6" }, svg);
  el("path", { d: "M132,100 C120,112 100,118 70,124", stroke: "#E0A800", "stroke-width": 6, fill: "none", "stroke-linecap": "round" }, svg);
  var t = function (x, y, s, a, c) { txt(svg, x, y, s, { "text-anchor": a || "middle", "font-size": 12, fill: c || "#172B3A" }); };
  t(210, 60, "جسم الفقرة", "middle", "#8A5200");
  t(210, 170, "جسم الفقرة", "middle", "#8A5200");
  t(212, 113, "النواة", "middle", "#7B1F45");
  t(300, 113, "القرص", "middle", "#7A5A2E");
  t(132, 226, "القناة الشوكية", "middle", "#2F5E8F");
  t(60, 196, "جذر العصب يخرج", "middle", "#8A6500");
  t(60, 211, "من الثقب", "middle", "#8A6500");
  t(300, 14, "الأمام", "middle", "#5B6B7F");
  t(30, 14, "الخلف", "middle", "#5B6B7F");
  return svg;
}

/* ---------- Small surgery diagrams (side view, front on the right) ---------- */
function drawSurgery(kind) {
  var svg = svgRoot(300, 150, "رسم توضيحي للعملية");
  var bone = { fill: "#FFF4E0", stroke: "#B26A00", "stroke-width": 1.4 };
  var top = el("rect", Object.assign({ x: 130, y: 14, width: 120, height: 48, rx: 8 }, bone), svg);
  el("rect", Object.assign({ x: 130, y: 88, width: 120, height: 48, rx: 8 }, bone), svg);
  el("path", Object.assign({ d: "M130,26 L60,30 L56,46 L130,52 Z" }, bone), svg);
  el("path", Object.assign({ d: "M130,100 L60,104 L56,120 L130,126 Z" }, bone), svg);
  el("rect", { x: 104, y: 6, width: 20, height: 140, rx: 8, fill: "#DCEBFA", stroke: "#7AA7D6" }, svg);
  var t = function (x, y, s, c) { txt(svg, x, y, s, { "font-size": 11, fill: c || "#172B3A" }); };
  var disc = function () { el("rect", { x: 132, y: 64, width: 116, height: 22, rx: 6, fill: "#F3E3C8", stroke: "#B8925A" }, svg); };

  if (kind === "discectomy" || kind === "endoscopic") {
    disc();
    el("path", { d: "M134,68 Q118,75 134,82 Z", fill: "#EC407A", stroke: "#C2185B", "stroke-dasharray": "3 2" }, svg);
    if (kind === "discectomy") {
      el("path", { d: "M10,40 L116,74", stroke: "#5B6B7F", "stroke-width": 3, "stroke-linecap": "round" }, svg);
      el("path", { d: "M10,40 L30,46", stroke: "#5B6B7F", "stroke-width": 6, "stroke-linecap": "round" }, svg);
      t(52, 70, "أداة دقيقة", "#5B6B7F");
    } else {
      el("path", { d: "M6,120 L112,80", stroke: "#2F6FA8", "stroke-width": 9, "stroke-linecap": "round", opacity: .8 }, svg);
      t(46, 140, "أنبوب المنظار", "#2F6FA8");
    }
    t(190, 80, "يُزال الجزء البارز فقط", "#C2185B");
  } else if (kind === "laminectomy") {
    disc();
    el("path", { d: "M100,22 L70,24 L66,58 L100,58 Z", fill: "#fff", stroke: "#C62828", "stroke-width": 1.5, "stroke-dasharray": "4 3" }, svg);
    t(82, 76, "جزء العظم", "#C62828"); t(82, 90, "الذي يُزال", "#C62828");
    t(190, 80, "القرص", "#7A5A2E");
  } else if (kind === "fusion") {
    el("rect", { x: 150, y: 64, width: 80, height: 22, rx: 3, fill: "#B0BEC5", stroke: "#546E7A" }, svg);
    t(190, 79, "قفص وطُعم عظمي", "#263238");
    el("path", { d: "M64,38 L128,38 M64,112 L128,112", stroke: "#546E7A", "stroke-width": 4, "stroke-linecap": "round" }, svg);
    el("path", { d: "M58,32 V118", stroke: "#37474F", "stroke-width": 6, "stroke-linecap": "round" }, svg);
    t(30, 80, "مسامير", "#37474F"); t(30, 94, "وقضيب", "#37474F");
  } else if (kind === "acdf") {
    el("rect", { x: 150, y: 64, width: 80, height: 22, rx: 3, fill: "#B0BEC5", stroke: "#546E7A" }, svg);
    el("rect", { x: 252, y: 30, width: 8, height: 90, rx: 3, fill: "#78909C" }, svg);
    el("circle", { cx: 256, cy: 40, r: 3, fill: "#37474F" }, svg); el("circle", { cx: 256, cy: 110, r: 3, fill: "#37474F" }, svg);
    t(190, 79, "قفص", "#263238"); t(282, 80, "صفيحة", "#37474F");
    t(282, 94, "أمامية", "#37474F");
  } else if (kind === "replacement") {
    el("rect", { x: 140, y: 63, width: 100, height: 5, rx: 2, fill: "#78909C" }, svg);
    el("rect", { x: 140, y: 82, width: 100, height: 5, rx: 2, fill: "#78909C" }, svg);
    el("ellipse", { cx: 190, cy: 75, rx: 34, ry: 7, fill: "#64B5F6", stroke: "#1E5FA0" }, svg);
    t(190, 102, "قرص صناعي متحرك", "#1E5FA0");
  }
  return svg;
}

/* Tiny icons for the home tiles. */
var ICONS = {
  spine: '<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><rect x="8" y="2.5" width="8" height="3.5" rx="1.5"/><rect x="7.5" y="8" width="9" height="3.5" rx="1.5"/><rect x="7" y="13.5" width="10" height="3.5" rx="1.5"/><path d="M9 19.5h6l-3 2.5z"/></svg>',
  disc: '<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8"><ellipse cx="12" cy="11" rx="9" ry="6"/><ellipse cx="12" cy="11" rx="4" ry="2.4"/><path d="M8 19h8" stroke-linecap="round"/></svg>',
  stages: '<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="5" cy="12" r="2.5"/><circle cx="12" cy="12" r="2.5"/><path d="M14.5 12h1.5"/><circle cx="19.5" cy="12" r="2.5"/></svg>',
  direction: '<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M12 4v8M12 12l-6 6M12 12l6 6M12 12h8"/></svg>',
  mine: '<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z"/><circle cx="12" cy="10" r="2.5"/></svg>',
  treat: '<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M4 20h4v-4h4v-4h4V8h4"/></svg>',
  video: '<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><rect x="3" y="5" width="18" height="14" rx="3"/><path d="M10 9l5 3-5 3z"/></svg>',
  alert: '<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"><path d="M12 3l9.5 17h-19z" stroke-linejoin="round"/><path d="M12 10v4M12 17v.5"/></svg>',
  book: '<svg viewBox="0 0 24 24" width="26" height="26" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"><path d="M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2z"/><path d="M4 21V5"/></svg>',
  play: '<svg viewBox="0 0 24 24" width="26" height="26"><path d="M8 5.5v13l11-6.5z" fill="#C62828"/></svg>'
};
