/* «ظهري» — section «افهم حالتك». Hash-routed pages, no network needed except for videos. */
"use strict";

var view = document.getElementById("view");
var titleEl = document.getElementById("title");
var backBtn = document.getElementById("back");

function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
function h(html) { var d = document.createElement("div"); d.innerHTML = html; return d.firstElementChild; }
function store(key, val) {
  try {
    if (val === undefined) return JSON.parse(localStorage.getItem(key) || "null");
    localStorage.setItem(key, JSON.stringify(val));
  } catch (e) { return null; }
}
var DISCLAIMER = '<p class="disclaimer">هذه معلومات عامة للفهم فقط، ولا تغني عن الطبيب. القرار في التشخيص والعلاج لطبيبك.</p>';
var EMERGENCY_LINK = '<a class="alert" href="#/emergency"><span style="color:#C62828">' + ICONS.alert + '</span><span><b>متى أذهب للطوارئ فوراً؟</b><br><span class="muted">خدر السرج، أو فقدان التحكم في البول أو البراز، أو ضعف مفاجئ في الساقين.</span></span></a>';

/* Keep codes like L4-L5 or S1 in the right order inside Arabic sentences («وS1»). */
function isolateCodes(rootEl) {
  var walker = document.createTreeWalker(rootEl, NodeFilter.SHOW_TEXT, {
    acceptNode: function (n) {
      for (var p = n.parentNode; p && p !== rootEl; p = p.parentNode) if (p.namespaceURI === SVGNS || p.tagName === "BDI" || p.tagName === "OPTION") return NodeFilter.FILTER_REJECT;
      return /[A-Za-z]/.test(n.nodeValue) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT;
    }
  }), nodes = [];
  while (walker.nextNode()) nodes.push(walker.currentNode);
  nodes.forEach(function (n) {
    var parts = n.nodeValue.split(/([A-Za-z][A-Za-z0-9\/]*(?:[ \-][A-Za-z0-9\/]+)*)/);
    if (parts.length < 2) return;
    var frag = document.createDocumentFragment();
    parts.forEach(function (part, i) {
      if (i % 2) { var b = document.createElement("bdi"); b.textContent = part; frag.appendChild(b); }
      else if (part) frag.appendChild(document.createTextNode(part));
    });
    n.parentNode.replaceChild(frag, n);
  });
}

/* 3D model (when the phone supports it) above the simple 2D cross-section; both follow the same shape. */
function discViews(box, labels) {
  var m = model3D(), a = axialWidget(labels);
  if (m) {
    box.appendChild(m.el);
    box.appendChild(h(LEGEND_3D));
    box.appendChild(h('<p class="caption" style="margin-top:10px">مقطع مبسط من أعلى:</p>'));
  }
  box.appendChild(a.svg);
  return { go: function (p) { if (m) m.go(p); a.go(p); } };
}

/* ---------- bottom sheet ---------- */
var sheet = document.getElementById("sheet"), backdrop = document.getElementById("sheetBackdrop");
function openSheet(html) {
  var body = document.getElementById("sheetBody");
  body.innerHTML = html;
  isolateCodes(body);
  if (sheet.hidden) history.pushState({ sheet: 1 }, "", location.href);
  sheet.hidden = false; backdrop.hidden = false;
  sheet.scrollTop = 0;
}
function hideSheet() { sheet.hidden = true; backdrop.hidden = true; }
function closeSheet() { if (!sheet.hidden) history.back(); }
document.getElementById("sheetClose").onclick = closeSheet;
backdrop.onclick = closeSheet;
window.addEventListener("popstate", function () { if (!sheet.hidden) hideSheet(); });

/* ---------- pages ---------- */
var PAGES = {};

PAGES[""] = { title: "ظهري", render: function () {
  var tiles = [
    ["model", "مجسم ثلاثي الأبعاد", "قلّب الفقرات والقرص بإصبعك من كل الجهات", "#E8F3FC", "#07543F"],
    ["spine", "العمود الفقري", "المس أي فقرة لتعرف اسمها وأعصابها", "#E8F3FC", "#2F6FA8"],
    ["disc", "تركيب القرص", "النواة والحلقة الليفية وجذور الأعصاب", "#FFF4E0", "#8A5200"],
    ["stages", "درجات الانزلاق", "من الانتفاخ إلى الانفصال، بالحركة", "#FCE7EF", "#C2185B"],
    ["direction", "اتجاه الانزلاق", "لماذا يتغير مكان الألم حسب الاتجاه", "#F1EAF7", "#7A4FA0"],
    ["mine", "حالتي أنا", "أدخل مستوى الانزلاق من تقرير الرنين", "#E8F5EE", "#07543F"],
    ["treatment", "طرق العلاج", "من الأدوية والعلاج الطبيعي إلى الجراحة", "#E8F5EE", "#21804B"],
    ["videos", "فيديوهات", "شروحات مرئية على يوتيوب", "#FDECEC", "#C62828"],
    ["sources", "المصادر", "من أين جاءت هذه المعلومات", "#F5F7FA", "#5B6B7F"]
  ];
  var html = '<div><div class="hero"><h2>افهم حالتك</h2><p>شرح مبسط بالرسوم للعمود الفقري والانزلاق الغضروفي وطرق علاجه.</p></div>' +
    EMERGENCY_LINK + '<div class="grid">' +
    tiles.map(function (t) {
      var icon = { model: "cube", spine: "spine", disc: "disc", stages: "stages", direction: "direction", mine: "mine", treatment: "treat", videos: "video", sources: "book" }[t[0]];
      return '<a class="tile" href="#/' + t[0] + '"><span class="ico" style="background:' + t[3] + ';color:' + t[4] + '">' + ICONS[icon] + '</span><strong>' + t[1] + '</strong><span>' + t[2] + '</span></a>';
    }).join("") +
    '</div><h2>قريباً في التطبيق</h2><div class="grid">' +
    ["يوميات الألم", "الأدوية والتنبيهات", "تمارين العلاج الطبيعي", "المواعيد والتقارير"].map(function (s) {
      return '<div class="tile soon"><strong>' + s + '</strong><span>في النسخة القادمة</span></div>';
    }).join("") + '</div>' + DISCLAIMER + '</div>';
  return h(html);
} };

PAGES.model = { title: "مجسم ثلاثي الأبعاد", render: function () {
  var root = h('<div><p>مجسم لفقرتين من أسفل الظهر وبينهما القرص، وخلفه الكيس العصبي وجذور الأعصاب. اختر درجة الانزلاق واتجاهه وشاهد التغيير من أي زاوية.</p>' +
    '<div class="figure" id="ax"></div>' +
    '<h3>درجة الانزلاق</h3><div class="chips" id="st"></div><h3>الاتجاه</h3><div class="chips" id="dr"></div>' +
    '<div class="card" id="txt"></div>' + DISCLAIMER + '</div>');
  var w = discViews(root.querySelector("#ax"), false), si = 3, di = 1;
  var stBox = root.querySelector("#st"), drBox = root.querySelector("#dr"), txt = root.querySelector("#txt");
  function apply() {
    [].forEach.call(stBox.children, function (c, j) { c.setAttribute("aria-pressed", j === si); });
    [].forEach.call(drBox.children, function (c, j) { c.setAttribute("aria-pressed", j === di); });
    var st = STAGES[si], d = DIRECTIONS[di];
    txt.innerHTML = '<h3>' + st.name + ' — ' + d.name + '</h3><p>' + st.what + '</p><p>' + d.hits + '</p>';
    isolateCodes(txt);
    w.go(Object.assign({ theta: d.theta }, st.p));
  }
  STAGES.forEach(function (st, i) { var b = h('<button class="chip">' + st.name + '</button>'); b.onclick = function () { si = i; apply(); }; stBox.appendChild(b); });
  DIRECTIONS.forEach(function (d, i) { var b = h('<button class="chip">' + d.name + '</button>'); b.onclick = function () { di = i; apply(); }; drBox.appendChild(b); });
  apply();
  return root;
} };

PAGES.spine = { title: "العمود الفقري", render: function () {
  var root = h('<div><p>العمود الفقري 33 فقرة تقريباً: 7 عنقية، و12 صدرية، و5 قطنية، ثم العجز والعصعص. بين كل فقرتين قرص غضروفي يمتص الصدمات.</p>' +
    '<div class="note">المس أي <b>فقرة</b> أو أي <b>قرص</b> (الخط الأخضر بين الفقرات) لتظهر لك معلوماته.</div>' +
    '<div class="figure" id="fig"></div>' + DISCLAIMER + '</div>');
  root.querySelector("#fig").appendChild(drawSpine({ onPick: showSpineInfo }));
  return root;
} };

function rootBlock(code) {
  var r = rootInfo(code);
  return '<div class="card" style="margin:8px 0;padding:12px"><h3>جذر العصب <span class="ltr">' + code + '</span></h3><dl class="kv">' +
    '<dt>الإحساس</dt><dd>' + r.feel + '</dd><dt>الحركة</dt><dd>' + r.move + '</dd>' +
    (r.reflex ? '<dt>المنعكس</dt><dd>' + r.reflex + '</dd>' : '') + '</dl></div>';
}
function regionTag(r) { var g = REGIONS[r]; return '<span class="tag" style="background:' + g.fill + ';color:' + g.stroke + '">' + g.name + '</span>'; }

function showSpineInfo(kind, code) {
  var html;
  if (kind === "v") {
    var r = code[0], roots = rootsAtVertebra(code);
    html = regionTag(r) + '<h3>' + vertebraName(code) + ' (<span class="ltr">' + code + '</span>)</h3>' +
      (SPECIAL[code] ? '<p>' + SPECIAL[code] + '</p>' : '') + '<p class="muted">' + REGIONS[r].about + '</p>' +
      '<h3 style="margin-top:12px">الأعصاب التي تخرج عندها</h3>' +
      (r === "C" ? '<p class="muted">في الرقبة يخرج كل جذر فوق الفقرة التي تحمل رقمه، وجذر C8 يخرج تحت C7.</p>'
                 : '<p class="muted">في الظهر يخرج كل جذر تحت الفقرة التي تحمل رقمه.</p>') +
      roots.map(rootBlock).join("");
  } else if (kind === "d") {
    var parts = code.split("-"), up = parts[0], lo = parts[1], rr = discRoots(up, lo), reg = up[0];
    var note = DISC_NOTES[code] || (reg === "T" ? DISC_NOTES.T : "");
    html = regionTag(reg) + '<h3>القرص بين <span class="ltr">' + up + '</span> و<span class="ltr">' + lo + '</span></h3>' +
      '<p>يُكتب في تقرير الرنين هكذا: <b class="ltr">' + code + '</b></p>' + (note ? '<div class="note warn">' + note + '</div>' : '');
    if (reg === "L") {
      html += '<p>العصب الذي يتأثر يعتمد على اتجاه الانزلاق:</p>' +
        '<p><b>خلفي جانبي (الأشيع):</b> يضغط عادة على جذر <span class="ltr">' + rr.paracentral + '</span></p>' + rootBlock(rr.paracentral) +
        '<p><b>داخل الثقب أو خارجه:</b> يضغط على جذر <span class="ltr">' + rr.foraminal + '</span></p>' + rootBlock(rr.foraminal) +
        '<p class="muted"><b>المركزي الكبير</b> قد يضغط على حزمة «ذيل الفرس» كلها.</p>';
    } else {
      html += '<p>الانزلاق الجانبي هنا يضغط عادة على جذر <span class="ltr">' + rr.paracentral + '</span>.</p>' + rootBlock(rr.paracentral) +
        '<p class="muted">والانزلاق المركزي الكبير قد يضغط على الحبل الشوكي نفسه.</p>';
    }
    html += '<a class="btn outline" href="#/mine?level=' + code + '" style="margin-top:8px">هذا مستوى انزلاقي</a>';
  } else {
    html = regionTag("S") + '<h3>العجز والعصعص</h3><p>' + REGIONS.S.about + '</p>' + ["S1", "S2", "S3"].map(rootBlock).join("");
  }
  openSheet(html);
}

PAGES.disc = { title: "تركيب القرص", render: function () {
  var root = h('<div><p>القرص الغضروفي وسادة بين جسمي فقرتين. له جزءان:</p>' +
    '<div class="card"><h3>الحلقة الليفية</h3><p>طبقات متينة من الألياف ملفوفة حول بعضها مثل حلقات جذع الشجرة، تحفظ ما بداخلها.</p>' +
    '<h3>النواة اللبية</h3><p>مادة هلامية في الوسط توزع الضغط عند الحركة والجلوس والحمل.</p></div>' +
    '<div class="figure" id="side"></div><p class="caption">منظر جانبي: القرص بين فقرتين، وخلفه القناة الشوكية التي تمر فيها الأعصاب.</p>' +
    '<p>عندما تضعف الحلقة الليفية أو تتمزق، تندفع النواة إلى الخارج. وإذا وصلت إلى جذر عصب ضغطت عليه وسببت التهابه، فينتشر الألم أو التنميل على طول مسار هذا العصب إلى الساق أو الذراع.</p>' +
    '<div class="figure" id="ax"></div><p class="caption">مقطع عرضي من أعلى: القرص في الأمام، والكيس العصبي وجذور الأعصاب (بالأصفر) خلفه.</p>' +
    '<a class="btn" href="#/stages">شاهد درجات الانزلاق</a>' + DISCLAIMER + '</div>');
  root.querySelector("#side").appendChild(drawMotionSegment());
  var w = axialWidget(true); root.querySelector("#ax").appendChild(w.svg);
  return root;
} };

PAGES.stages = { title: "درجات الانزلاق", render: function () {
  var root = h('<div><p>يصنّف الأطباء الانزلاق في تقرير الرنين بحسب مدى خروج النواة من القرص. اضغط على كل درجة لترى الفرق.</p>' +
    '<div class="chips" id="chips"></div><div class="figure" id="ax"></div>' +
    '<div class="row" style="justify-content:center;margin-bottom:12px"><button class="btn outline" id="play">▶ شاهد التدرج كاملاً</button></div>' +
    '<div class="card stage-text" id="txt"></div>' +
    '<div class="note good">' + REGRESSION_NOTE + '</div>' +
    '<div class="note">درجة الانزلاق في الرنين لا تساوي شدة الألم. كثيرون عندهم انتفاخ أو بروز بلا أي أعراض، وقرار العلاج يُبنى على الأعراض والفحص مع الصورة.</div>' +
    DISCLAIMER + '</div>');
  var w = discViews(root.querySelector("#ax"), false);
  var chips = root.querySelector("#chips"), txtBox = root.querySelector("#txt"), timer = null;
  function select(i, fromPlay) {
    if (!fromPlay && timer) { clearTimeout(timer); timer = null; }
    var s = STAGES[i];
    [].forEach.call(chips.children, function (c, j) { c.setAttribute("aria-pressed", j === i); });
    txtBox.innerHTML = '<h3>' + s.name + ' <span class="muted ltr">' + s.en + '</span></h3><p>' + s.what + '</p><p><b>الأعراض المعتادة:</b> ' + s.feel + '</p>';
    isolateCodes(txtBox);
    w.go(Object.assign({ theta: 1.18 }, s.p));
  }
  STAGES.forEach(function (s, i) {
    var b = h('<button class="chip">' + s.name + '</button>'); b.onclick = function () { select(i); }; chips.appendChild(b);
  });
  root.querySelector("#play").onclick = function () {
    var i = 0;
    (function next() { select(i, true); i++; if (i < STAGES.length) timer = setTimeout(next, 2200); else timer = null; })();
  };
  select(0);
  return root;
} };

PAGES.direction = { title: "اتجاه الانزلاق", render: function () {
  var root = h('<div><p>اتجاه الانزلاق يحدد <b>أي عصب</b> يُضغط عليه، ولذلك يختلف مكان الألم والتنميل من شخص لآخر حتى لو كان الانزلاق في نفس المستوى.</p>' +
    '<div class="chips" id="chips"></div><div class="figure" id="ax"></div>' +
    '<p class="caption">الأصفر: جذور الأعصاب. يتحول العصب إلى الأحمر عندما يضغط عليه الانزلاق.</p>' +
    '<div class="card" id="txt"></div>' + DISCLAIMER + '</div>');
  var w = discViews(root.querySelector("#ax"), true);
  var chips = root.querySelector("#chips"), box = root.querySelector("#txt");
  function select(i) {
    var d = DIRECTIONS[i];
    [].forEach.call(chips.children, function (c, j) { c.setAttribute("aria-pressed", j === i); });
    box.innerHTML = '<h3>' + d.name + ' <span class="muted ltr">' + d.en + '</span></h3><p>' + d.what + '</p><p>' + d.hits + '</p>';
    isolateCodes(box);
    w.go({ theta: d.theta, bulge: .2, prot: 1, len: 1, head: .85, tear: 1, free: 0 });
  }
  DIRECTIONS.forEach(function (d, i) {
    var b = h('<button class="chip">' + d.name + '</button>'); b.onclick = function () { select(i); }; chips.appendChild(b);
  });
  select(1);
  return root;
} };

function discLevels() {
  var out = [];
  SPINE_LIST.forEach(function (v, i) {
    if (v.code === "C1") return;
    var lower = SPINE_LIST[i + 1] ? SPINE_LIST[i + 1].code : "S1";
    out.push(v.code + "-" + lower);
  });
  return out;
}

PAGES.mine = { title: "حالتي أنا", render: function (q) {
  var saved = store("dahri.mine") || { level: "L4-L5", stage: "protrusion", dir: "paracentral" };
  if (q.level) saved.level = q.level;
  var levels = discLevels();
  var root = h('<div><p>انقل من تقرير الرنين مستوى الانزلاق ونوعه واتجاهه، فيظهر لك مكانه والعصب الذي يتأثر به عادة.</p>' +
    '<div class="card">' +
    '<label class="field">مستوى الانزلاق<select id="lv">' + levels.map(function (l) { return '<option' + (l === saved.level ? ' selected' : '') + '>' + l + '</option>'; }).join("") + '</select></label>' +
    '<label class="field">النوع<select id="st">' + STAGES.slice(1).map(function (s) { return '<option value="' + s.id + '"' + (s.id === saved.stage ? ' selected' : '') + '>' + s.name + ' (' + s.en + ')</option>'; }).join("") + '<option value="unknown"' + (saved.stage === "unknown" ? " selected" : "") + '>لا أعرف</option></select></label>' +
    '<label class="field">الاتجاه<select id="dr">' + DIRECTIONS.map(function (d) { return '<option value="' + d.id + '"' + (d.id === saved.dir ? ' selected' : '') + '>' + d.name + ' (' + d.en + ')</option>'; }).join("") + '<option value="unknown"' + (saved.dir === "unknown" ? " selected" : "") + '>غير مذكور / لا أعرف</option></select></label>' +
    '<p class="muted">كلمات قد تجدها في التقرير: Left أي يسار، Right أي يمين، Posterior أي خلفي، Broad-based أي واسع القاعدة، Nerve root compression أي ضغط على جذر عصب.</p>' +
    '</div><div class="card" id="res"></div>' +
    '<div class="figure" id="ax"></div>' +
    '<div class="figure" id="sp"></div>' + DISCLAIMER + '</div>');
  var w = discViews(root.querySelector("#ax"), true);
  var lv = root.querySelector("#lv"), st = root.querySelector("#st"), dr = root.querySelector("#dr");
  function update() {
    var s = { level: lv.value, stage: st.value, dir: dr.value };
    store("dahri.mine", s);
    var parts = s.level.split("-"), rr = discRoots(parts[0], parts[1]), reg = parts[0][0];
    var dir = DIRECTIONS.filter(function (d) { return d.id === s.dir; })[0] || DIRECTIONS[1];
    var stage = STAGES.filter(function (x) { return x.id === s.stage; })[0] || STAGES[2];
    var html = '<h3>انزلاقك في <span class="ltr">' + s.level + '</span></h3><p class="muted">' + REGIONS[reg].name + '</p>';
    if (s.stage === "bulge") {
      html += '<p>الانتفاخ واسع ومنتظم، وغالباً لا يضغط على عصب بعينه إلا إذا كانت القناة أو الثقب ضيقين أصلاً. إن وُجد ضغط يذكره التقرير عادة.</p>';
    } else {
      var root2 = s.dir === "unknown" ? rr.paracentral : rr[dir.id];
      if (s.dir === "unknown") html += '<p>أشيع اتجاه هو الخلفي الجانبي، وفيه يتأثر عادة:</p>';
      if (/^[CTLS]\d/.test(root2)) html += '<p>العصب الذي يتأثر عادة: <b>جذر <span class="ltr">' + root2 + '</span></b></p>' + rootBlock(root2);
      else html += '<p>قد يضغط على <b>' + root2 + '</b> إن كان كبيراً. راجع صفحة <a href="#/emergency">الطوارئ</a> لتعرف علامات الخطر.</p>';
    }
    html += '<div class="note">قارن هذا بما يشعر به: هل يطابق مكانُ الألم أو التنميل المنطقةَ المذكورة؟ التشريح يختلف قليلاً من شخص لآخر، والمرجع هو تقرير الرنين وفحص الطبيب.</div>';
    if (DISC_NOTES[s.level]) html += '<p class="muted">' + DISC_NOTES[s.level] + '</p>';
    root.querySelector("#res").innerHTML = html; isolateCodes(root.querySelector("#res"));
    var p = s.stage === "unknown" ? STAGES[2].p : stage.p;
    w.go(Object.assign({ theta: dir.theta }, p));
    var sp = root.querySelector("#sp"); sp.innerHTML = "";
    sp.appendChild(drawSpine({ highlight: s.level, onPick: function (k, c) { if (k === "d") { lv.value = c; update(); window.scrollTo({ top: 0, behavior: "smooth" }); } } }));
  }
  [lv, st, dr].forEach(function (x) { x.onchange = update; });
  setTimeout(update, 0);
  return root;
} };

PAGES.treatment = { title: "طرق العلاج", render: function () {
  var root = h('<div><p>يتدرج العلاج عادة من الأبسط إلى الأكبر. أغلب المرضى يتحسنون بالخطوات الأولى، والجراحة لحالات محددة.</p><div id="acc"></div>' +
    '<div class="note warn">ما يناسب حالة صديقك بالضبط يقرره طبيبه بعد الفحص والرنين. هذه الصفحة تساعده على فهم الخيارات وتجهيز أسئلته.</div>' + DISCLAIMER + '</div>');
  var acc = root.querySelector("#acc");
  TREATMENT.forEach(function (t, i) {
    var d = h('<details class="acc"><summary><span class="step" style="background:' + t.color + '">' + (i + 1) + '</span><span class="t">' + t.title + '<small>' + t.sub + '</small></span></summary><div class="body">' + t.html + '</div></details>');
    if (t.surgery) {
      var body = d.querySelector(".body");
      SURGERIES.forEach(function (s) {
        var sub = h('<div class="sub"><h4>' + s.name + ' <span class="muted ltr">' + s.en + '</span></h4><div class="pic"></div>' +
          '<p>' + s.what + '</p><p><b>متى:</b> ' + s.when + '</p><p><b>التعافي التقريبي:</b> ' + s.rec + '</p><p class="muted">' + s.note + '</p></div>');
        sub.querySelector(".pic").appendChild(drawSurgery(s.pic));
        body.appendChild(sub);
      });
      body.appendChild(h('<div class="note"><b>أسئلة يجهزها للجراح:</b><ul><li>ما نوع العملية التي تقترحها لحالتي، ولماذا هي بالذات؟</li><li>ما الذي يحدث لو انتظرت وواصلت العلاج التحفظي؟</li><li>ما نسبة النجاح المتوقعة لحالتي، وما المخاطر؟</li><li>كم يوماً في المستشفى، ومتى أعود للعمل والقيادة؟</li><li>هل سأحتاج علاجاً طبيعياً بعد العملية، ومتى أبدأ؟</li></ul></div>'));
    }
    acc.appendChild(d);
  });
  return root;
} };

PAGES.videos = { title: "فيديوهات", render: function () {
  var mine = store("dahri.videos") || [];
  var root = h('<div><div class="note">الفيديوهات تحتاج إنترنت وتفتح في تطبيق يوتيوب. اخترتها من أطباء عرب ومن شركات رسوم طبية ثلاثية الأبعاد. المقاطع الإنجليزية رسومها واضحة حتى دون فهم الكلام، ويمكن تشغيل الترجمة العربية التلقائية من إعدادات يوتيوب.</div>' +
    '<div id="list"></div><h2>فيديوهات حفظتها</h2><p class="muted">احفظ هنا فيديو أرسله لك طبيبك أو أخصائيك لتجده بسهولة.</p><div id="mine"></div>' +
    '<div class="card"><label class="field">عنوان الفيديو<input type="text" id="vt" placeholder="مثال: تمارين من أخصائي العلاج الطبيعي"></label>' +
    '<label class="field">الرابط<input type="url" id="vu" placeholder="https://youtube.com/..." dir="ltr"></label>' +
    '<p class="muted" id="verr" hidden style="color:#C62828">الرابط يجب أن يبدأ بـ https://</p>' +
    '<button class="btn" id="add">حفظ الفيديو</button></div>' +
    '<div class="note warn">لا يطبق صديقك تمارين من فيديو إلا بعد موافقة أخصائيه، لأن الحالة متقدمة.</div>' + DISCLAIMER + '</div>');
  var list = root.querySelector("#list");
  VIDEO_GROUPS.forEach(function (g) {
    list.appendChild(h('<h2>' + g.t + '</h2>'));
    g.items.forEach(function (v) {
      var url = v.short ? "https://www.youtube.com/shorts/" + v.id : "https://www.youtube.com/watch?v=" + v.id;
      var card = h('<a class="card vcard" href="' + url + '"><span class="thumb"><img alt="" loading="lazy" src="https://i.ytimg.com/vi/' + v.id + '/mqdefault.jpg"><span class="pbtn">' + ICONS.play + '</span></span>' +
        '<span class="vt"><b>' + v.t + '</b><small>' + v.by + (v.en ? ' · بالإنجليزية' : '') + '</small></span></a>');
      var img = card.querySelector("img");
      // A removed video only has YouTube's small grey placeholder (120px wide): hide its card.
      img.onload = function () { if (img.naturalWidth <= 120) card.remove(); };
      img.onerror = function () { card.querySelector(".thumb").classList.add("off"); };
      list.appendChild(card);
    });
  });
  list.appendChild(h('<a class="btn outline" style="margin-top:4px" href="https://www.youtube.com/results?search_query=' + encodeURIComponent("الانزلاق الغضروفي شرح") + '">ابحث عن المزيد في يوتيوب</a>'));
  var mineBox = root.querySelector("#mine");
  function drawMine() {
    mineBox.innerHTML = mine.length ? "" : '<p class="muted">لا يوجد شيء بعد.</p>';
    mine.forEach(function (v, i) {
      var row = h('<div class="card video"><span class="play">' + ICONS.play + '</span><a class="vt" style="color:inherit;text-decoration:none" href="' + esc(v.u) + '"><b>' + esc(v.t) + '</b><small class="ltr">' + esc(v.u.slice(0, 48)) + '</small></a><button class="chip" aria-label="حذف">حذف</button></div>');
      row.querySelector("button").onclick = function () { mine.splice(i, 1); store("dahri.videos", mine); drawMine(); };
      mineBox.appendChild(row);
    });
  }
  root.querySelector("#add").onclick = function () {
    var t = root.querySelector("#vt").value.trim(), u = root.querySelector("#vu").value.trim();
    var ok = /^https:\/\/\S+$/.test(u);
    root.querySelector("#verr").hidden = ok;
    if (!ok) return;
    mine.push({ t: t || "فيديو", u: u }); store("dahri.videos", mine);
    root.querySelector("#vt").value = ""; root.querySelector("#vu").value = "";
    drawMine();
  };
  drawMine();
  return root;
} };

PAGES.emergency = { title: "متى أذهب للطوارئ؟", render: function () {
  return h('<div><div class="card" style="background:#FDECEC;border-color:#F5C6C6"><h3 style="color:#C62828">توجّه إلى الطوارئ فوراً إذا ظهرت أي علامة من هذه</h3>' +
    '<p>بعض هذه العلامات قد تدل على «متلازمة ذيل الفرس»، وهي ضغط شديد على أعصاب أسفل العمود الفقري، وتحتاج علاجاً عاجلاً خلال ساعات لحماية الأعصاب من الضرر الدائم.</p></div>' +
    RED_FLAGS.map(function (f) { return '<div class="card"><h3>' + f.t + '</h3><p class="muted">' + f.d + '</p></div>'; }).join("") +
    '<div class="row" style="margin:16px 0"><a class="btn danger" href="tel:123">اتصال بالإسعاف (123 في مصر)</a></div>' +
    '<p class="muted">إن كان صديقك خارج مصر فليتصل برقم الإسعاف في بلده.</p>' + DISCLAIMER + '</div>');
} };

PAGES.sources = { title: "المصادر", render: function () {
  return h('<div><p>كُتبت المعلومات في هذا القسم اعتماداً على هذه المراجع الطبية. الأفضل أن يراجعها طبيب صديقك أو أخصائيه، خاصة جزء الجراحة.</p>' +
    SOURCES.map(function (s) {
      return '<div class="card">' + (s.u ? '<a href="' + s.u + '" style="color:#07543F">' + s.t + '</a>' : s.t) + '</div>';
    }).join("") +
    '<p class="muted">الرسوم في التطبيق رسوم توضيحية أصلية مبسطة، وليست بمقاييس تشريحية دقيقة.</p>' + DISCLAIMER + '</div>');
} };

/* ---------- router ---------- */
function route() {
  hideSheet();
  var hash = location.hash.replace(/^#\/?/, ""), qi = hash.indexOf("?");
  var name = qi >= 0 ? hash.slice(0, qi) : hash, q = {};
  if (qi >= 0) hash.slice(qi + 1).split("&").forEach(function (kv) { var p = kv.split("="); q[p[0]] = decodeURIComponent(p[1] || ""); });
  var page = PAGES[name] || PAGES[""];
  titleEl.textContent = page.title;
  backBtn.hidden = !name;
  view.innerHTML = "";
  view.appendChild(page.render(q));
  isolateCodes(view);
  window.scrollTo(0, 0);
}
backBtn.onclick = function () { if (history.length > 1) history.back(); else location.hash = ""; };
window.addEventListener("hashchange", route);
route();
