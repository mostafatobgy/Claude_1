/* 3D model of the whole lower back (L1–L5, sacrum, coccyx) in the style of a teaching model:
   ivory bone, translucent amber discs, yellow nerve roots, and a red herniated disc at a chosen level.
   Original procedural geometry, drawn with three.js. Drag to turn, pinch to zoom. */
"use strict";

var LUMBAR_LEVELS = ["L1-L2", "L2-L3", "L3-L4", "L4-L5", "L5-S1"];

function spineModel3D(opts) {
  opts = opts || {};
  if (!window.THREE || !webglOK()) return null;
  var T = THREE;
  var wrap = document.createElement("div");
  wrap.className = "m3d spine3d";
  var canvas = document.createElement("canvas");
  wrap.appendChild(canvas);
  var labelLayer = document.createElement("div");
  labelLayer.className = "m3d-labels";
  wrap.appendChild(labelLayer);
  var hint = document.createElement("div");
  hint.className = "m3d-hint";
  hint.textContent = "اسحب بإصبعك لتدوير المجسم، وباصبعين للتكبير";
  wrap.appendChild(hint);

  var renderer = new T.WebGLRenderer({ canvas: canvas, antialias: true, alpha: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  var scene = new T.Scene();
  var camera = new T.PerspectiveCamera(32, 1, 0.5, 300);
  scene.add(new T.HemisphereLight(0xffffff, 0x7d6f5c, 0.6));
  var key = new T.DirectionalLight(0xffffff, 0.75); key.position.set(20, 25, 30); scene.add(key);
  var rim = new T.DirectionalLight(0xfff3dd, 0.45); rim.position.set(-25, 10, -20); scene.add(rim);
  var under = new T.DirectionalLight(0xffffff, 0.2); under.position.set(0, -30, 10); scene.add(under);

  var M = {
    bone: new T.MeshStandardMaterial({ color: 0xF1E8D6, roughness: 0.55, metalness: 0.0 }),
    boneDark: new T.MeshStandardMaterial({ color: 0x9C8B72, roughness: 0.8 }),
    disc: new T.MeshPhysicalMaterial({ color: 0xE9B65A, roughness: 0.25, transparent: true, opacity: 0.72, clearcoat: 0.8, clearcoatRoughness: 0.3 }),
    nerve: function () { return new T.MeshStandardMaterial({ color: 0xF5C211, roughness: 0.45, emissive: 0x2a1d00 }); },
    hernia: new T.MeshStandardMaterial({ color: 0xD3122B, roughness: 0.35, emissive: 0x3a0008 }),
    dura: new T.MeshStandardMaterial({ color: 0xF7D774, roughness: 0.5, transparent: true, opacity: 0.35, depthWrite: false })
  };
  var root = new T.Group(); scene.add(root);

  // ---------- one lumbar vertebra, centred on its body, front = +z ----------
  function waistedBody(r, h) {        // vertebral bodies are slightly hour-glass shaped with rounded rims
    var pts = [], n = 14;
    for (var i = 0; i <= n; i++) {
      var t = i / n, y = -h / 2 + t * h;
      var edge = Math.min(t, 1 - t) * h;                       // distance from the nearest end plate
      var rim = edge < 0.18 ? Math.sqrt(Math.max(0, 0.18 * 0.18 - (0.18 - edge) * (0.18 - edge))) / 0.18 : 1;
      var waist = 1 - 0.07 * Math.sin(Math.PI * t);
      pts.push(new T.Vector2(Math.max(0.001, r * waist * (0.86 + 0.14 * rim)), y));
    }
    pts.unshift(new T.Vector2(0.001, -h / 2)); pts.push(new T.Vector2(0.001, h / 2));
    var g = new T.LatheGeometry(pts, 40);
    g.scale(1.28, 1, 0.9);
    return g;
  }
  function capsule(r, len, mat) { return new T.Mesh(new T.CapsuleGeometry(r, len, 6, 12), mat); }
  function between(mesh, a, b) {      // stretch a y-aligned mesh between two points
    mesh.position.copy(a).add(b).multiplyScalar(0.5);
    mesh.quaternion.setFromUnitVectors(new T.Vector3(0, 1, 0), b.clone().sub(a).normalize());
    return mesh;
  }
  function V(x, y, z) { return new T.Vector3(x, y, z); }

  function vertebra(scale, h) {
    var g = new T.Group();
    var body = new T.Mesh(waistedBody(1.95 * scale, h), M.bone);
    body.position.z = 0.15; g.add(body);
    [-1, 1].forEach(function (s) {
      g.add(between(capsule(0.42, 1.0, M.bone), V(s * 1.05, 0.25, -1.3), V(s * 1.2, 0.3, -2.5)));          // pedicle
      g.add(between(capsule(0.34, 1.2, M.bone), V(s * 1.2, 0.3, -2.6), V(s * 0.25, 0.0, -3.6)));            // lamina
      var tp = between(capsule(0.3, 2.1, M.bone), V(s * 1.4, 0.35, -2.35), V(s * 3.7 * scale, 0.45, -2.0)); // transverse process
      tp.scale.set(1, 1, 0.7); g.add(tp);
      g.add(between(capsule(0.3, 0.5, M.bone), V(s * 1.15, 0.6, -2.75), V(s * 1.25, h * 0.55 + 0.35, -2.85))); // upper joint
      g.add(between(capsule(0.28, 0.5, M.bone), V(s * 0.95, -0.1, -3.2), V(s * 0.9, -h * 0.5 - 0.2, -3.25))); // lower joint
    });
    var sp = between(capsule(0.42, 2.0, M.bone), V(0, 0.0, -3.5), V(0, -0.7, -5.9));                          // spinous process
    sp.scale.set(0.55, 1, 1.25); g.add(sp);
    return g;
  }

  // ---------- stack L1..L5 with the lumbar curve ----------
  var sizes = [{ s: 0.92, h: 2.2 }, { s: 0.96, h: 2.3 }, { s: 1.0, h: 2.4 }, { s: 1.04, h: 2.45 }, { s: 1.08, h: 2.4 }];
  var DISC = 0.95;
  var levels = [];                    // per vertebra: {pos, ang, h}
  var theta = -0.3, pos = V(0, 10.5, 0);
  sizes.forEach(function (sz, i) {
    if (i > 0) {
      var prev = levels[i - 1], step = prev.h / 2 + DISC + sz.h / 2, mid = (prev.ang + theta) / 2;
      pos = prev.pos.clone().add(V(0, -Math.cos(mid) * step, -Math.sin(mid) * step));
    }
    var v = vertebra(sz.s, sz.h);
    v.position.copy(pos); v.rotation.x = theta; root.add(v);
    levels.push({ pos: pos.clone(), ang: theta, h: sz.h, s: sz.s, obj: v });
    theta += 0.14;
  });
  function localToWorld(lv, p) { return p.clone().applyAxisAngle(V(1, 0, 0), lv.ang).add(lv.pos); }

  // sacrum: a curved wedge tilted backwards, with holes for the sacral nerves
  var L5 = levels[4], sacAng = L5.ang + 0.32;
  var sacTop = localToWorld(L5, V(0, -L5.h / 2 - DISC - 0.1, -0.1));
  var sacrum = new T.Group();
  var shape = new T.Shape();
  shape.moveTo(-3.3, 0); shape.quadraticCurveTo(-3.7, -1.6, -2.5, -3.4); shape.quadraticCurveTo(-1.4, -5.6, -0.6, -6.6);
  shape.lineTo(0.6, -6.6); shape.quadraticCurveTo(1.4, -5.6, 2.5, -3.4); shape.quadraticCurveTo(3.7, -1.6, 3.3, 0);
  shape.quadraticCurveTo(0, 0.5, -3.3, 0);
  var sg = new T.ExtrudeGeometry(shape, { depth: 2.8, bevelEnabled: true, bevelThickness: 0.5, bevelSize: 0.4, bevelSegments: 4, curveSegments: 16 });
  sg.translate(0, 0, -2.2);
  // bend it so the front is concave, like the real sacrum
  var p = sg.attributes.position;
  for (var k = 0; k < p.count; k++) { var yy = p.getY(k); p.setZ(k, p.getZ(k) + 0.05 * yy * yy); }
  sg.computeVertexNormals();
  sacrum.add(new T.Mesh(sg, M.bone));
  var sacFor = [];
  for (var f = 0; f < 4; f++) {        // anterior sacral foramina (dark pits) on both sides
    [-1, 1].forEach(function (s) {
      var y = -1.0 - f * 1.35, x = s * (1.7 - f * 0.28), z = 1.1 + 0.05 * y * y;
      var pit = new T.Mesh(new T.CircleGeometry(0.33 - f * 0.04, 16), M.boneDark);
      pit.position.set(x, y, z + 0.01); sacrum.add(pit);
      sacFor.push({ s: s, f: f, p: V(x, y, z) });
    });
  }
  sacrum.position.copy(sacTop); sacrum.rotation.x = sacAng; root.add(sacrum);
  function sacToWorld(v) { return v.clone().applyAxisAngle(V(1, 0, 0), sacAng).add(sacTop); }

  // coccyx: a few small bones curling forward
  var cPos = sacToWorld(V(0, -7.1, 0.2 + 0.05 * 49)), cAng = sacAng + 0.35;
  [0.55, 0.45, 0.36].forEach(function (r) {
    var c = capsule(r, r * 0.8, M.bone); c.scale.set(1.4, 1, 1);
    c.position.copy(cPos); c.rotation.x = cAng; root.add(c);
    cPos = cPos.clone().add(V(0, -Math.cos(cAng) * r * 2.1, -Math.sin(cAng) * r * 2.1)); cAng += 0.3;
  });

  // ---------- discs ----------
  var discMeshes = [];
  for (var i = 0; i < 5; i++) {
    var up = levels[i], ang = i < 4 ? (up.ang + levels[i + 1].ang) / 2 : (up.ang + sacAng) / 2 - 0.15;
    var c = localToWorld(up, V(0, -up.h / 2 - DISC / 2, 0.15));
    var dg = new T.CylinderGeometry(1.95 * up.s * 0.98, 1.95 * up.s * 0.98, DISC * (i === 4 ? 1.15 : 1), 40);
    dg.scale(1.28, 1, 0.9);
    var d = new T.Mesh(dg, M.disc);
    d.position.copy(c); d.rotation.x = ang; d.renderOrder = 2; root.add(d);
    discMeshes.push({ mesh: d, center: c, ang: ang });
  }

  // ---------- nerves: the dura, and one root on each side under each pedicle ----------
  var duraPts = levels.map(function (lv) { return localToWorld(lv, V(0, 0, -2.5)); });
  duraPts.unshift(localToWorld(levels[0], V(0, 2.2, -2.5)));
  duraPts.push(sacToWorld(V(0, -2.5, -0.6)));
  root.add(new T.Mesh(new T.TubeGeometry(new T.CatmullRomCurve3(duraPts), 60, 0.85, 14, false), M.dura));

  var roots = {};                      // name → [right material, left material]
  function rootTube(pts) { var m = M.nerve(); root.add(new T.Mesh(new T.TubeGeometry(new T.CatmullRomCurve3(pts), 40, 0.2, 8, false), m)); return m; }
  levels.forEach(function (lv, i) {    // root Ln leaves below vertebra Ln
    roots["L" + (i + 1)] = [1, -1].map(function (s) {
      var y0 = -lv.h / 2 + 0.05;
      return rootTube([localToWorld(lv, V(s * 0.6, lv.h / 2 + DISC + 0.5, -2.45)), localToWorld(lv, V(s * 0.75, 0.2, -2.5)), localToWorld(lv, V(s * 1.6, y0, -2.2)),
        localToWorld(lv, V(s * 2.6, y0 - 0.7, -1.7)), localToWorld(lv, V(s * 3.7, y0 - 1.6, -1.3)), localToWorld(lv, V(s * 4.8, y0 - 2.6, -0.9))]);
    });
  });
  sacFor.forEach(function (sf) {       // S1..S4 come out of the sacral holes
    var name = "S" + (sf.f + 1), s = sf.s;
    var a = sacToWorld(V(s * (1.2 - sf.f * 0.25), sf.p.y + 0.3, -0.4)), b = sacToWorld(sf.p.clone().add(V(0, 0, 0.1)));
    var c2 = sacToWorld(sf.p.clone().add(V(s * 1.3, -0.9, 0.8))), d2 = sacToWorld(sf.p.clone().add(V(s * 1.9, -2.0, 1.1)));
    var path = [a, b, c2, d2];
    if (sf.f === 0) path.unshift(localToWorld(L5, V(s * 0.6, -L5.h / 2 - 0.3, -2.45)));   // S1 passes the L5-S1 disc first
    var m = rootTube(path);
    (roots[name] = roots[name] || [])[s > 0 ? 0 : 1] = m;
  });

  // ---------- herniations ----------
  // The patient faces +z, so the patient's LEFT is +x. roots[name][0] is the left root, [1] the right.
  // A finding: { level: "L5-S1", side: "L" | "R" | "B" (both) | "C" (centre), type: "para" | "foram" | "central" | "bulge", mild: bool }
  var herniaGroup = new T.Group(); root.add(herniaGroup);
  var M_mild = new T.MeshStandardMaterial({ color: 0xF08A24, roughness: 0.4, emissive: 0x3a1a00 });
  var M_bulge = new T.MeshStandardMaterial({ color: 0xF08A24, roughness: 0.4, transparent: true, opacity: 0.55, depthWrite: false });
  var pressed = [];                    // [{ mat, severe }]
  var spots = [];                      // where to put labels: [{ p, text, severe }]
  function rootFor(f, sideSign) {      // which root a finding presses on, on one side
    var parts = f.level.split("-"), idx = sideSign > 0 ? 0 : 1;
    var name = f.type === "foram" ? parts[0] : parts[1];   // through the exit hole: the root leaving at this level; back-and-side: the one going down
    return roots[name] ? { mat: roots[name][idx], name: name } : null;
  }
  function setCase(list) {
    while (herniaGroup.children.length) { var c = herniaGroup.children.pop(); if (c.geometry !== sphereGeo) c.geometry.dispose(); }
    Object.keys(roots).forEach(function (n) { roots[n].forEach(function (m) { m.color.setHex(0xF5C211); m.emissive.setHex(0x2a1d00); }); });
    pressed = []; spots = [];
    (list || []).forEach(function (f) {
      var i = LUMBAR_LEVELS.indexOf(f.level); if (i < 0) return;
      var dm = discMeshes[i];
      if (f.type === "bulge") {        // broad bulge all round the back of the disc
        var shell = new T.Mesh(dm.mesh.geometry.clone(), M_bulge);
        shell.scale.set(1.08, 1.05, 1.12); shell.position.copy(dm.center); shell.rotation.x = dm.ang; herniaGroup.add(shell);
        return;
      }
      var sides = f.side === "B" ? [1, -1] : f.side === "R" ? [-1] : f.side === "C" ? [0] : [1];
      sides.forEach(function (sg) {
        var local = f.type === "foram" ? V(sg * 2.15, 0, -1.0) : f.type === "central" || sg === 0 ? V(0, 0, -1.75) : V(sg * 1.0, 0, -1.5);
        var m = new T.Mesh(sphereGeo, f.mild ? M_mild : M.hernia);
        var k = f.mild ? 0.62 : 1;
        m.scale.set(0.85 * k, 0.5 * k, 0.7 * k);
        m.position.copy(dm.center).add(local.applyAxisAngle(V(1, 0, 0), dm.ang));
        m.rotation.x = dm.ang; herniaGroup.add(m);
        spots.push({ p: m.position.clone(), text: f.level, severe: !f.mild });
        if (sg !== 0) {
          var r = rootFor(f, sg);
          if (r) {
            var old = pressed.filter(function (x) { return x.mat === r.mat; })[0];
            if (old) old.severe = old.severe || !f.mild;
            else pressed.push({ mat: r.mat, severe: !f.mild, name: r.name, side: sg, at: f });
          }
        }
      });
    });
    refreshCaseLabels();
  }
  var sphereGeo = new T.SphereGeometry(1, 24, 16);
  function setLevel(level) { setCase(level ? [{ level: level, side: "L", type: "para" }] : []); }

  // ---------- labels that follow the model ----------
  var showLabels = true;
  var LABELS = [
    { t: "القرص الغضروفي", at: function () { return discMeshes[1].center.clone().add(V(2.5, 0, 0.4)); } },
    { t: "النتوء الشوكي", at: function () { return localToWorld(levels[1], V(0, -0.8, -6.2)); } },
    { t: "جذر عصب", at: function () { return localToWorld(levels[2], V(-4.0, -4.6, 0.5)); } },
    { t: "العجز", at: function () { return sacToWorld(V(2.6, -2.6, 1.2)); } },
    { t: "العصعص", at: function () { return cPos.clone(); } }
  ];
  levels.forEach(function (lv, i) { LABELS.push({ t: "L" + (i + 1), cls: "lv", at: function () { return localToWorld(lv, V(-2.9, 0, 0.8)); } }); });
  // which side is which (patient's left is +x)
  LABELS.push({ t: "يسار", cls: "lv", at: function () { return sacToWorld(V(5.2, -1.0, 0.8)); } });
  LABELS.push({ t: "يمين", cls: "lv", at: function () { return sacToWorld(V(-5.2, -1.0, 0.8)); } });
  function makeLabel(l) { l.el = document.createElement("span"); l.el.className = "m3d-label " + (l.cls || ""); l.el.textContent = l.t; labelLayer.appendChild(l.el); }
  LABELS.forEach(makeLabel);
  var caseLabels = [];
  function refreshCaseLabels() {       // herniation and pressed-root labels go first so they win any overlap
    if (!labelLayer) return;
    caseLabels.forEach(function (l) { l.el.remove(); });
    caseLabels = [];
    var seen = {};
    spots.forEach(function (sp) {
      if (seen[sp.text]) return; seen[sp.text] = 1;
      caseLabels.push({ t: sp.text, cls: sp.severe ? "red" : "orange", at: function () { return sp.p.clone(); } });
    });
    pressed.forEach(function (pr) {
      var lv = pr.name[0] === "S" ? null : levels[+pr.name.slice(1) - 1];
      var pt = lv ? localToWorld(lv, V(pr.side * 4.4, -lv.h / 2 - 2.0, -0.9)) : sacToWorld(V(pr.side * 2.6, -1.8, 1.6));
      caseLabels.push({ t: "عصب " + pr.name + " " + (pr.side > 0 ? "يسار" : "يمين"), cls: pr.severe ? "red" : "orange", at: function () { return pt.clone(); } });
    });
    caseLabels.forEach(makeLabel);
  }
  if (opts.findings) setCase(opts.findings); else setLevel(opts.level === undefined ? "L4-L5" : opts.level);
  function placeLabels(w, h) {
    var shown = [];                    // skip a label that would cover one already placed
    caseLabels.concat(LABELS).forEach(function (l) {
      var p3 = showLabels && l.at();
      if (!p3) { l.el.style.display = "none"; return; }
      var v = p3.clone().applyMatrix4(root.matrixWorld).project(camera);
      var x = (v.x + 1) / 2 * w, y = (1 - v.y) / 2 * h;
      l.el.style.display = "";
      var bw = l.el.offsetWidth || 60, bh = l.el.offsetHeight || 18;
      var r = { x0: x - bw / 2, x1: x + bw / 2, y0: y - bh / 2, y1: y + bh / 2 };
      if (shown.some(function (o) { return r.x0 < o.x1 && r.x1 > o.x0 && r.y0 < o.y1 && r.y1 > o.y0; })) { l.el.style.visibility = "hidden"; return; }
      l.el.style.visibility = "";
      shown.push(r);
      l.el.style.transform = "translate(" + x.toFixed(1) + "px," + y.toFixed(1) + "px) translate(-50%,-50%)";
    });
  }

  // centre the model
  var box = new T.Box3().setFromObject(root), centre = box.getCenter(V(0, 0, 0));
  root.position.sub(centre);

  // ---------- camera: drag to orbit, pinch to zoom ----------
  var view = { az: opts.az !== undefined ? opts.az : 0.95, el: opts.el !== undefined ? opts.el : 0.12, r: 47 }, target = V(0, 0, 0), touched = !!opts.still;
  attachOrbit(wrap, canvas, view, 22, 110, 1.2, function () { touched = true; hint.style.opacity = 0; });

  var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var started = performance.now(), lastW = 0, lastH = 0;
  function frame(ts) {
    if (!wrap.isConnected && performance.now() - started > 2000) { renderer.dispose(); return; }
    requestAnimationFrame(frame);
    if (!wrap.isConnected) return;
    var w = wrap.clientWidth || 320, h = wrap.closest(".fs") ? wrap.clientHeight || w : Math.round(w * 1.15);
    if (w !== lastW || h !== lastH) { renderer.setSize(w, h, false); canvas.style.width = w + "px"; canvas.style.height = h + "px"; camera.aspect = w / h; camera.updateProjectionMatrix(); lastW = w; lastH = h; }
    if (!touched && !reduce) view.az += 0.004;
    var g = 0.5 + 0.5 * Math.sin(ts / 220);
    pressed.forEach(function (pr) {    // red pulse = touching / pressing, orange pulse = mild narrowing
      if (pr.severe) { pr.mat.color.setRGB(0.85 + 0.15 * g, 0.12, 0.12); pr.mat.emissive.setRGB(0.35 * g, 0, 0); }
      else { pr.mat.color.setRGB(0.96, 0.5 + 0.12 * g, 0.1); pr.mat.emissive.setRGB(0.25 * g, 0.1 * g, 0); }
    });
    camera.position.set(target.x + view.r * Math.sin(view.az) * Math.cos(view.el), target.y + view.r * Math.sin(view.el), target.z + view.r * Math.cos(view.az) * Math.cos(view.el));
    camera.lookAt(target);
    root.updateMatrixWorld();
    renderer.render(scene, camera);
    placeLabels(w, h);
  }
  requestAnimationFrame(frame);

  return {
    el: wrap,
    setLevel: setLevel,
    setCase: setCase,
    toggleLabels: function () { showLabels = !showLabels; return showLabels; }
  };
}
