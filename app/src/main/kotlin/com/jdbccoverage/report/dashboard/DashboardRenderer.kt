package com.jdbccoverage.report.dashboard

import com.fasterxml.jackson.databind.SerializationFeature
import com.jdbccoverage.report.json.createObjectMapper

/**
 * Renders the trend dashboard as a single self-contained HTML page:
 * data embedded as JSON, chart drawn by inline JS into an SVG, zero external
 * requests (works offline and on GitHub Pages alike).
 */
class DashboardRenderer {

    private val mapper = createObjectMapper().copy().disable(SerializationFeature.INDENT_OUTPUT)

    fun render(drivers: List<DriverDashboardData>): String {
        val payload = drivers.mapIndexed { i, d ->
            val last = d.entries.last()
            val prev = d.entries.dropLast(1).lastOrNull()
            mapOf<String, Any?>(
                "name" to last.driverName,
                "slug" to d.slug,
                "color" to PALETTE_LIGHT[i % PALETTE_LIGHT.size],
                "colorDark" to PALETTE_DARK[i % PALETTE_DARK.size],
                "series" to d.entries.map { mapOf("date" to it.date, "percent" to it.overallPercent) },
                "last" to last,
                "prevPercent" to prev?.overallPercent,
                "groups" to last.groups.map {
                    mapOf("group" to it.group, "implemented" to it.implemented, "total" to it.total)
                },
                "interfaces" to (
                    d.latest?.interfaces?.sortedBy { it.interfaceName.substringAfterLast('.') }?.map { iface ->
                        mapOf(
                            "name" to iface.interfaceName.substringAfterLast('.'),
                            "percent" to iface.coveragePercent,
                            "implemented" to iface.implemented,
                            "stub" to iface.stub,
                            "notFound" to iface.notFound,
                            "total" to iface.total,
                        )
                    } ?: emptyList()
                    ),
            )
        }
        // </script> inside the JSON would close the data block early — escape it.
        val json = mapper.writeValueAsString(payload).replace("</", "<\\/")
        return TEMPLATE.replace("__DATA__", json)
    }

    companion object {
        // Validated categorical palette (dataviz six-checks, light surface):
        // worst adjacent CVD deltaE 24.2. Slots assigned by sorted slug order.
        val PALETTE_LIGHT = listOf("#2a78d6", "#1baf7a", "#eda100", "#008300", "#4a3aa7")
        val PALETTE_DARK = listOf("#3987e5", "#199e70", "#c98500", "#008300", "#9085e9")
    }
}

private val TEMPLATE = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>JDBC API Coverage</title>
<style>
:root {
  --surface: #fcfcfb; --text: #1a1a19; --text-2: #57564e; --grid: #e4e3dd; --card: #f4f3ef;
}
@media (prefers-color-scheme: dark) {
  :root { --surface: #1a1a19; --text: #ffffff; --text-2: #c3c2b7; --grid: #3a3935; --card: #242320; }
}
* { box-sizing: border-box; }
body { margin: 2rem auto; max-width: 1000px; padding: 0 1rem; background: var(--surface);
       color: var(--text); font: 15px/1.55 system-ui, sans-serif; }
h1 { font-size: 1.5rem; margin-bottom: .25rem; }
h2 { font-size: 1.1rem; margin: 2rem 0 .5rem; }
.meta { color: var(--text-2); font-size: .85rem; margin-top: 0; }
#chart-wrap { position: relative; overflow-x: auto; }
#tip { position: absolute; pointer-events: none; background: var(--card); color: var(--text);
       border: 1px solid var(--grid); border-radius: 6px; padding: .4rem .6rem;
       font-size: .8rem; white-space: nowrap; }
#legend { display: flex; flex-wrap: wrap; gap: 1rem; margin-top: .5rem; font-size: .85rem; }
#legend .chip { display: inline-block; width: 12px; height: 12px; border-radius: 3px;
                margin-right: .35rem; vertical-align: -1px; }
table { border-collapse: collapse; width: 100%; font-size: .9rem; }
th, td { text-align: left; padding: .35rem .6rem; border-bottom: 1px solid var(--grid); }
th { color: var(--text-2); font-weight: 600; }
td.num, th.num { text-align: right; font-variant-numeric: tabular-nums; }
.up { color: #008300; } .down { color: #b3261e; } .flat { color: var(--text-2); }
details { margin: .5rem 0; } summary { cursor: pointer; font-weight: 600; }
.change { font-family: ui-monospace, monospace; font-size: .82rem; }
.spec-note { color: var(--text-2); font-size: .8rem; }
</style>
</head>
<body>
<h1>JDBC API Coverage</h1>
<p class="meta" id="meta"></p>

<h2>Trend</h2>
<div id="chart-wrap">
  <svg id="chart" viewBox="0 0 960 380" width="960" height="380" role="img" aria-label="Coverage trend by driver"></svg>
  <div id="tip" hidden></div>
</div>
<div id="legend"></div>

<h2>Today</h2>
<table id="today">
  <thead><tr><th>Driver</th><th class="num">Coverage</th><th class="num">vs prev</th>
  <th class="num">&le;4.2</th><th class="num">Impl</th><th class="num">Stub</th>
  <th class="num">Missing</th><th>Commit</th></tr></thead>
  <tbody></tbody>
</table>

<h2>Changes since previous run</h2>
<div id="changes"></div>

<h2>Per-driver detail</h2>
<div id="details"></div>

<script type="application/json" id="data">__DATA__</script>
<script>
"use strict";
var data = JSON.parse(document.getElementById("data").textContent);
var dark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
function colorOf(d) { return dark ? d.colorDark : d.color; }
function fmt(x) { return x == null ? "-" : x.toFixed(1); }

// meta line
var allDates = [];
data.forEach(function (d) { d.series.forEach(function (p) { allDates.push(p.date); }); });
allDates = Array.from(new Set(allDates)).sort();
document.getElementById("meta").textContent =
  "Generated " + allDates[allDates.length - 1] + " - spec " + data[0].last.specVersion +
  " - tool v" + data[0].last.toolVersion;

// ---- trend chart -----------------------------------------------------------
var W = 960, H = 380, PAD = { l: 48, r: 130, t: 16, b: 32 };
var iw = W - PAD.l - PAD.r, ih = H - PAD.t - PAD.b;
var svgNS = "http://www.w3.org/2000/svg";
var svg = document.getElementById("chart");
function el(tag, attrs, text) {
  var e = document.createElementNS(svgNS, tag);
  Object.keys(attrs).forEach(function (k) { e.setAttribute(k, attrs[k]); });
  if (text) e.textContent = text;
  return e;
}
function x(i) { return PAD.l + (allDates.length < 2 ? iw / 2 : i * iw / (allDates.length - 1)); }
function y(p) { return PAD.t + ih - (p / 100) * ih; }
var css = getComputedStyle(document.documentElement);
var gridColor = css.getPropertyValue("--grid").trim();
var text2 = css.getPropertyValue("--text-2").trim();
[0, 20, 40, 60, 80, 100].forEach(function (v) {
  svg.appendChild(el("line", { x1: PAD.l, y1: y(v), x2: W - PAD.r, y2: y(v), stroke: gridColor, "stroke-width": 1 }));
  svg.appendChild(el("text", { x: PAD.l - 8, y: y(v) + 4, "text-anchor": "end", fill: text2, "font-size": 11 }, String(v)));
});
var first = allDates[0], mid = allDates[Math.floor((allDates.length - 1) / 2)], lastD = allDates[allDates.length - 1];
[[first, 0], [mid, Math.floor((allDates.length - 1) / 2)], [lastD, allDates.length - 1]].forEach(function (pair) {
  svg.appendChild(el("text", { x: x(pair[1]), y: H - 8, "text-anchor": "middle", fill: text2, "font-size": 11 }, pair[0]));
});
data.forEach(function (d) {
  var pts = d.series.map(function (p) { return x(allDates.indexOf(p.date)) + "," + y(p.percent); }).join(" ");
  svg.appendChild(el("polyline", { points: pts, fill: "none", stroke: colorOf(d), "stroke-width": 2,
    "stroke-linejoin": "round", "stroke-linecap": "round" }));
  var lastP = d.series[d.series.length - 1];
  var lx = x(allDates.indexOf(lastP.date)), ly = y(lastP.percent);
  svg.appendChild(el("circle", { cx: lx, cy: ly, r: 4, fill: colorOf(d) }));
  svg.appendChild(el("text", { x: lx + 8, y: ly + 4, fill: colorOf(d), "font-size": 12, "font-weight": 600 },
    d.name + " " + fmt(lastP.percent)));
});

// hover crosshair + tooltip
var tip = document.getElementById("tip");
var cross = el("line", { y1: PAD.t, y2: PAD.t + ih, stroke: text2, "stroke-width": 1, "stroke-dasharray": "3,3" });
cross.setAttribute("visibility", "hidden");
svg.appendChild(cross);
svg.addEventListener("mousemove", function (ev) {
  var rect = svg.getBoundingClientRect();
  var mx = (ev.clientX - rect.left) * (W / rect.width);
  var idx = 0, best = 1e9;
  allDates.forEach(function (d, i) { var dist = Math.abs(x(i) - mx); if (dist < best) { best = dist; idx = i; } });
  cross.setAttribute("x1", x(idx)); cross.setAttribute("x2", x(idx));
  cross.setAttribute("visibility", "visible");
  var lines = ["<b>" + allDates[idx] + "</b>"];
  data.forEach(function (d) {
    var p = d.series.filter(function (s) { return s.date === allDates[idx]; })[0];
    if (p) lines.push('<span class="chip" style="background:' + colorOf(d) + '"></span>' + d.name + " " + fmt(p.percent) + "%");
  });
  tip.innerHTML = lines.join("<br>");
  tip.hidden = false;
  tip.style.left = Math.min(x(idx) / W * 100, 75) + "%";
  tip.style.top = "10px";
});
svg.addEventListener("mouseleave", function () { tip.hidden = true; cross.setAttribute("visibility", "hidden"); });

// legend
var legend = document.getElementById("legend");
data.forEach(function (d) {
  var span = document.createElement("span");
  span.innerHTML = '<span class="chip" style="background:' + colorOf(d) + '"></span>' + d.name;
  legend.appendChild(span);
});

// ---- today table -----------------------------------------------------------
var tbody = document.querySelector("#today tbody");
data.forEach(function (d) {
  var last = d.last;
  var delta = d.prevPercent == null ? null : last.overallPercent - d.prevPercent;
  var deltaHtml = delta == null ? '<span class="flat">-</span>'
    : Math.abs(delta) < 0.05 ? '<span class="flat">=</span>'
    : delta > 0 ? '<span class="up">+' + delta.toFixed(1) + '</span>'
    : '<span class="down">' + delta.toFixed(1) + '</span>';
  var c42 = (last.cumulative || []).filter(function (c) { return c.version === "4.2"; })[0];
  var c42Html = c42 ? (100 * c42.implemented / c42.total).toFixed(1) : "-";
  var commit = last.sourceCommit ? last.sourceCommit.substring(0, 10) : "-";
  var tr = document.createElement("tr");
  tr.innerHTML = '<td><span class="chip" style="background:' + colorOf(d) + '"></span>' + last.driverName + "</td>" +
    '<td class="num"><b>' + fmt(last.overallPercent) + '%</b></td>' +
    '<td class="num">' + deltaHtml + "</td>" +
    '<td class="num">' + c42Html + "%</td>" +
    '<td class="num">' + last.implemented + "</td>" +
    '<td class="num">' + last.stub + "</td>" +
    '<td class="num">' + last.notFound + "</td>" +
    "<td><code>" + commit + "</code></td>";
  tbody.appendChild(tr);
});

// ---- changes ---------------------------------------------------------------
var changesDiv = document.getElementById("changes");
data.forEach(function (d) {
  var h = document.createElement("h3");
  h.textContent = d.name;
  h.style.fontSize = ".95rem";
  changesDiv.appendChild(h);
  if (d.last.specChanged) {
    var note = document.createElement("p");
    note.className = "spec-note";
    note.textContent = "Spec version changed - deltas not comparable for this run.";
    changesDiv.appendChild(note);
  }
  var changes = d.last.changes || [];
  if (changes.length === 0) {
    var p = document.createElement("p");
    p.className = "spec-note";
    p.textContent = "No changes.";
    changesDiv.appendChild(p);
  } else {
    changes.forEach(function (c) {
      var line = document.createElement("div");
      line.className = "change";
      line.textContent = c.method + ": " + c.before + " -> " + c.after;
      changesDiv.appendChild(line);
    });
  }
});

// ---- per-driver detail -----------------------------------------------------
var details = document.getElementById("details");
data.forEach(function (d) {
  var det = document.createElement("details");
  var sum = document.createElement("summary");
  sum.textContent = d.name;
  det.appendChild(sum);
  // By-group coverage: headline is JDBC (MAIN); Peripheral and XA/JTA are reported separately.
  var groups = d.groups || [];
  var groupLabel = { MAIN: "JDBC (core)", PERIPHERAL: "Peripheral", XA: "XA / JTA" };
  var groupRows = groups.map(function (g) {
    var pct = g.total === 0 ? 0 : 100 * g.implemented / g.total;
    return "<tr><td>" + (groupLabel[g.group] || g.group) + '</td><td class="num">' +
      pct.toFixed(1) + '%</td><td class="num">' + g.implemented + "/" + g.total + "</td></tr>";
  }).join("");
  if (groupRows) {
    det.innerHTML += '<table><thead><tr><th>Group</th><th class="num">Coverage</th><th class="num">Impl/Total</th></tr></thead><tbody>' + groupRows + "</tbody></table>";
  }
  var cum = d.last.cumulative || [];
  // Per-version, not cumulative: each boundary minus the previous one. The
  // cumulative list only holds versions present in the spec, so consecutive
  // differences are exactly the methods introduced at that version.
  var rows = cum.map(function (c, i) {
    var prev = i > 0 ? cum[i - 1] : null;
    var impl = c.implemented - (prev ? prev.implemented : 0);
    var total = c.total - (prev ? prev.total : 0);
    var pct = total === 0 ? 0 : 100 * impl / total;
    return "<tr><td>" + c.version + '</td><td class="num">' +
      pct.toFixed(1) + '%</td><td class="num">' +
      impl + "/" + total + "</td></tr>";
  }).join("");
  var ifaceRows = (d.interfaces || []).map(function (f) {
    return "<tr><td>" + f.name + '</td><td class="num">' + fmt(f.percent) + '%</td><td class="num">' +
      f.implemented + '</td><td class="num">' + f.stub + '</td><td class="num">' + f.notFound + "</td></tr>";
  }).join("");
  det.innerHTML += '<table style="margin-top:.75rem"><thead><tr><th>Version</th><th class="num">Coverage</th><th class="num">Impl/Total</th></tr></thead><tbody>' + rows + "</tbody></table>";
  if (ifaceRows) {
    det.innerHTML += '<table style="margin-top:.75rem"><thead><tr><th>Interface</th><th class="num">Coverage</th><th class="num">Impl</th><th class="num">Stub</th><th class="num">Missing</th></tr></thead><tbody>' + ifaceRows + "</tbody></table>";
  }
  details.appendChild(det);
});
</script>
</body>
</html>
"""
