"""Writes compositions/setup.html and compositions/fix.html: Veo shots with HTML overlays.

Coordinates are pixels of the 1920x1080 shots. The monitor quads were measured on assets/gen/desk-plate.jpg.
"""
import sys

OUT = sys.argv[1]

LEFT = [(503, 104), (983, 104), (976, 449), (508, 412)]     # TL, TR, BR, BL of the left screen
RIGHT = [(998, 104), (1549, 116), (1519, 551), (991, 450)]  # the right screen
LED = (1357, 868)
LENS = (1235, 760)
FOOT = [(1010, 790), (1470, 790), (1515, 962), (965, 962)]
TERM = (132, 100, 1140, 880)  # the demo terminal rect

CODE = [
    (0, [(60, "#c084fc"), (120, "#e7e7ea")]), (0, []), (0, [(48, "#60a5fa"), (90, "#fbbf24"), (40, "#e7e7ea")]),
    (1, [(36, "#c084fc"), (70, "#e7e7ea"), (30, "#4ade80")]), (1, [(90, "#e7e7ea"), (60, "#fb923c")]),
    (2, [(110, "#60a5fa"), (40, "#e7e7ea")]), (2, [(70, "#e7e7ea"), (90, "#4ade80")]), (1, [(20, "#e7e7ea")]),
    (0, []), (0, [(52, "#c084fc"), (140, "#e7e7ea")]), (1, [(80, "#60a5fa"), (50, "#fbbf24")]),
    (1, [(120, "#e7e7ea")]), (2, [(60, "#fb923c"), (70, "#e7e7ea")]), (1, [(20, "#e7e7ea")]), (0, [(20, "#e7e7ea")]),
    (0, []), (0, [(64, "#c084fc"), (100, "#e7e7ea")]), (1, [(140, "#60a5fa"), (40, "#e7e7ea")]), (1, [(20, "#e7e7ea")]),
]


def code_html(p):
    rows = []
    for i, (ind, toks) in enumerate(CODE):
        bars = "".join(f'<i style="width:{w * 1.9:.0f}px;background:{c}"></i>' for w, c in toks)
        rows.append(f'<div class="cl"><b>{i + 1}</b><span style="padding-left:{ind * 56}px">{bars}</span></div>')
    return f"""<div id="{p}-code" class="scr code" data-layout-ignore>
        <div class="tabs"><i></i><i class="dim"></i></div>
        {''.join(rows)}
      </div>"""


def term_html(p, body):
    return f"""<div id="{p}-term" class="scr term" data-layout-ignore>
      <div class="bar"><i style="background:#ff5f57"></i><i style="background:#febc2e"></i><i style="background:#28c840"></i><span>claude · ~/dev/sensor-node</span></div>
      <div class="view">{body}</div>
    </div>"""


def fx_svg(p):
    poly = " ".join(f"{x},{y}" for x, y in [LENS, FOOT[3], FOOT[2], FOOT[1]])
    foot = " ".join(f"{x},{y}" for x, y in FOOT)
    return f"""<svg id="{p}-fx" class="fx" viewBox="0 0 1920 1080" width="1920" height="1080" data-layout-ignore>
        <defs>
          <radialGradient id="{p}-glow"><stop offset="0" stop-color="#f2fff2"/><stop offset="0.2" stop-color="#6ee7a0" stop-opacity="0.95"/><stop offset="0.55" stop-color="#22c55e" stop-opacity="0.35"/><stop offset="1" stop-color="#22c55e" stop-opacity="0"/></radialGradient>
          <linearGradient id="{p}-conefill" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffffff" stop-opacity="0.22"/><stop offset="1" stop-color="#ffffff" stop-opacity="0.06"/></linearGradient>
        </defs>
        <g id="{p}-cone" opacity="0">
          <polygon points="{poly}" fill="url(#{p}-conefill)"/>
          <polygon points="{foot}" fill="none" stroke="#ffffff" stroke-opacity="0.85" stroke-width="2.5" stroke-dasharray="12 9"/>
        </g>
        <ellipse id="{p}-glow" cx="{LED[0]}" cy="{LED[1]}" rx="34" ry="22" fill="url(#{p}-glow)" opacity="0" style="mix-blend-mode: screen"/>
      </svg>"""


CSS = """
    #root { position: absolute; inset: 0; overflow: hidden; background: #101216; font-family: Inter, sans-serif; }
    .bg { position: absolute; inset: 0; background: radial-gradient(ellipse at 70% 45%, #1f2633 0%, #101216 70%); }
    .cam { position: absolute; left: 0; top: 0; width: 1920px; height: 1080px; }
    .plate, .shot { position: absolute; left: 0; top: 0; width: 1920px; height: 1080px; }
    .fx { position: absolute; left: 0; top: 0; }
    .scr { position: absolute; left: 0; top: 0; width: 1140px; height: 880px; transform-origin: 0 0; overflow: hidden; }
    .term { background: #17181c; border-radius: 6px; }
    .term .bar { height: 44px; display: flex; align-items: center; gap: 9px; padding: 0 18px; background: #212228; border-bottom: 1px solid #2c2e35; }
    .term .bar i { width: 13px; height: 13px; border-radius: 50%; display: block; }
    .term .bar span { flex: 1; text-align: center; margin-right: 60px; color: #9a9ca5; font: 500 17px Inter, sans-serif; }
    .term .view { position: absolute; left: 0; right: 0; top: 44px; bottom: 0; padding: 24px 28px; font-family: "JetBrains Mono", monospace; font-size: 24px; line-height: 34px; color: #e7e7ea; }
    .term .user { background: #26272d; border-radius: 8px; padding: 12px 16px; }
    .term .user b { color: #8b8d96; font-weight: 400; }
    .code { background: #1e1f24; padding: 64px 0 0 0; }
    .code .tabs { position: absolute; left: 0; right: 0; top: 0; height: 44px; background: #26272d; display: flex; gap: 12px; align-items: center; padding: 0 16px; }
    .code .tabs i { display: block; width: 180px; height: 18px; border-radius: 4px; background: #3b3d45; }
    .code .tabs i.dim { width: 140px; background: #30323a; }
    .code .cl { display: flex; align-items: center; height: 40px; }
    .code .cl b { width: 70px; text-align: right; padding-right: 24px; color: #5b5e66; font: 400 20px "JetBrains Mono", monospace; }
    .code .cl span { display: flex; gap: 14px; }
    .code .cl span i { display: block; height: 18px; border-radius: 4px; opacity: 0.85; }
    .shade { position: absolute; inset: 0; background: rgba(0,0,0,0.0); }
    .lbl { position: absolute; z-index: 20; }
    .lbl .txt { position: absolute; white-space: nowrap; padding: 10px 18px; border-radius: 10px; background: rgba(16,18,22,0.9);
      color: #f5f5f4; font: 700 30px Inter, sans-serif; letter-spacing: -0.01em; box-shadow: 0 8px 24px rgba(0,0,0,0.35); }
    .lbl .txt em { font-style: normal; color: #4ade80; }
    .lbl svg { position: absolute; left: 0; top: 0; overflow: visible; }
"""


def label(id_, text, tx, ty, lx, ly):
    return f"""<div class="lbl" id="{id_}" style="left:{lx}px; top:{ly}px;">
      <svg width="1" height="1"><line id="{id_}-line" x1="0" y1="0" x2="{tx - lx}" y2="{ty - ly}" stroke="#f5f5f4" stroke-width="2.5" stroke-dasharray="900" stroke-dashoffset="900"/>
        <circle id="{id_}-dot" cx="{tx - lx}" cy="{ty - ly}" r="7" fill="#4ade80" stroke="#101216" stroke-width="2.5" opacity="0"/></svg>
      <div class="txt" id="{id_}-txt" style="left: 0; top: -28px;">{text}</div>
    </div>"""


JS = """
      // Maps an element of size w x h onto the quad q (TL, TR, BR, BL) with a CSS matrix3d.
      function quadMatrix(w, h, q) {
        const [[x0, y0], [x1, y1], [x2, y2], [x3, y3]] = q;
        const dx1 = x1 - x2, dx2 = x3 - x2, dx3 = x0 - x1 + x2 - x3;
        const dy1 = y1 - y2, dy2 = y3 - y2, dy3 = y0 - y1 + y2 - y3;
        let g = 0, hh = 0;
        if (Math.abs(dx3) > 1e-9 || Math.abs(dy3) > 1e-9) {
          const det = dx1 * dy2 - dx2 * dy1;
          g = (dx3 * dy2 - dx2 * dy3) / det;
          hh = (dx1 * dy3 - dx3 * dy1) / det;
        }
        const a = x1 - x0 + g * x1, b = x3 - x0 + hh * x3, c = x0;
        const d = y1 - y0 + g * y1, e = y3 - y0 + hh * y3, f = y0;
        const m = [a / w, d / w, 0, g / w, b / h, e / h, 0, hh / h, 0, 0, 1, 0, c, f, 0, 1];
        return "matrix3d(" + m.map((v) => +v.toFixed(8)).join(",") + ")";
      }
      function place(id, q) { document.getElementById(id).style.transform = quadMatrix(1140, 880, q); }
      // The STAT LED blinks 3 long, 2 short, every 5 s.
      function blinkCode(tl, sel, from, to) {
        const ON = [[0, 0.6], [0.9, 1.5], [1.8, 2.4], [3.2, 3.35], [3.65, 3.8]];
        for (let k = 0; k * 5 < to; k++) {
          ON.forEach(([a, b]) => {
            if (k * 5 + a >= from && k * 5 + a < to) tl.set(sel, { opacity: 1 }, k * 5 + a);
            if (k * 5 + b >= from && k * 5 + b < to) tl.set(sel, { opacity: 0 }, k * 5 + b);
          });
        }
      }
      function showLabel(tl, id, t, hold) {
        tl.fromTo("#" + id + "-line", { strokeDashoffset: 900 }, { strokeDashoffset: 0, duration: 0.35, ease: "power2.out" }, t);
        tl.fromTo("#" + id + "-dot", { opacity: 0, scale: 0.4, transformOrigin: "50% 50%" }, { opacity: 1, scale: 1, duration: 0.25, ease: "back.out(1.6)" }, t);
        tl.fromTo("#" + id + "-txt", { opacity: 0, filter: "blur(8px)" }, { opacity: 1, filter: "blur(0px)", duration: 0.18, ease: "power4.out" }, t + 0.2);
        tl.to("#" + id + "-txt", { opacity: 0, filter: "blur(6px)", duration: 0.15, ease: "power1.in" }, t + 0.2 + hold);
        tl.to("#" + id + "-line", { strokeDashoffset: -900, duration: 0.25, ease: "power2.in" }, t + 0.2 + hold);
        tl.to("#" + id + "-dot", { opacity: 0, duration: 0.15 }, t + 0.2 + hold);
      }
"""

q = lambda pts: "[" + ",".join(f"[{x},{y}]" for x, y in pts) + "]"

# ---------------- setup: shot A, labels, then the terminal lifts off the monitor ----------------
S = 2.4
CX, CY = 742, 267
TX, TY = 702 - S * CX, 540 - S * CY
FLAT = [(TERM[0], TERM[1]), (TERM[0] + TERM[2], TERM[1]), (TERM[0] + TERM[2], TERM[1] + TERM[3]), (TERM[0], TERM[1] + TERM[3])]

setup = f"""<template>
  <style>
    {CSS}
    #su-title {{ position: absolute; left: 960px; top: 26px; z-index: 20; padding: 18px 26px 20px; border-radius: 14px; background: rgba(16,18,22,0.88); color: #f5f5f4; }}
    #su-title .name {{ display: flex; align-items: center; gap: 14px; font-size: 52px; font-weight: 700; letter-spacing: -0.02em; line-height: 60px; }}
    #su-title .name img {{ width: 52px; height: 52px; }}
    #su-title p {{ margin-top: 6px; font-size: 30px; color: #c9ccd3; }}
  </style>
  <div id="root" data-composition-id="setup" data-width="1920" data-height="1080">
    <div class="bg"></div>
    <div id="su-cam" class="cam" data-layout-allow-overflow>
      <img id="su-plate" class="plate" src="assets/gen/shotA2-last.jpg" alt="" />
      <video id="su-shot" class="shot clip" src="assets/gen/shotA2.mp4" data-start="0" data-duration="8" data-track-index="1" muted playsinline></video>
      {code_html("su")}
      {fx_svg("su")}
    </div>
    {term_html("su", '<div id="su-prompt" class="user" style="opacity:0"><b>&gt; </b><span id="su-typed"></span></div>')}
    <div id="su-title"><div class="name"><img src="assets/icon.svg" alt="" />Lookout</div><p>A phone camera for your coding agent.</p></div>
    {label("su-l1", "The board you are debugging", 1250, 858, 1180, 990)}
    {label("su-l2", "A phone on an arm, camera facing down", 1235, 705, 1180, 190)}
    {label("su-l3", "Your coding agent", 742, 268, 560, 512)}
  </div>
  <script>
    (function () {{
      {JS}
      const LEFT = {q(LEFT)}, RIGHT = {q(RIGHT)}, FLAT = {q(FLAT)};
      place("su-code", RIGHT);
      place("su-term", LEFT);
      const tl = gsap.timeline({{ paused: true }});
      blinkCode(tl, "#su-glow", 0, 11);

      tl.fromTo("#su-title", {{ opacity: 0, filter: "blur(8px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.2, ease: "power4.out" }}, 0.2);
      tl.to("#su-title", {{ opacity: 0, filter: "blur(8px)", duration: 0.2, ease: "power1.in" }}, 2.9);
      showLabel(tl, "su-l1", 2.6, 2.0);
      showLabel(tl, "su-l2", 4.9, 2.4);
      tl.fromTo("#su-cone", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.4, ease: "power1.out" }}, 5.1);
      tl.to("#su-cone", {{ opacity: 0, duration: 0.3, ease: "power1.in" }}, 7.5);
      showLabel(tl, "su-l3", 7.5, 1.3);
      tl.fromTo("#su-prompt", {{ opacity: 0 }}, {{ opacity: 1, duration: 0.25 }}, 6.0);
      tl.set("#su-typed", {{ textContent: "My" }}, 10.95);

      // The camera pushes into the left monitor; the terminal lifts off the screen and lands flat
      // where the demo terminal is, so the cut at 11 s does not move.
      const cam = document.getElementById("su-cam");
      const push = {{ p: 0 }};
      const drawPush = () => {{
        const p = push.p, s = 1 + ({S} - 1) * p, x = {TX:.2f} * p, y = {TY:.2f} * p;
        gsap.set(cam, {{ x, y, scale: s, transformOrigin: "0 0" }});
        const w = Math.min(1, Math.max(0, (p - 0.35) / 0.65));
        const k = w * w * (3 - 2 * w);
        place("su-term", LEFT.map(([qx, qy], i) => [(x + s * qx) * (1 - k) + FLAT[i][0] * k, (y + s * qy) * (1 - k) + FLAT[i][1] * k]));
      }};
      tl.fromTo(push, {{ p: 0 }}, {{ p: 1, duration: 1.75, ease: "power3.inOut", onUpdate: drawPush }}, 9.2);
      tl.fromTo("#su-cam", {{ opacity: 1 }}, {{ opacity: 0, duration: 0.6, ease: "power1.in" }}, 10.25);
      tl.to({{}}, {{ duration: 0.01 }}, 10.99);
      window.__timelines["setup"] = tl;
    }})();
  </script>
</template>
"""

# ---------------- fix: shot B, the LED goes steady, punch-in, label ----------------
PS, PX, PY = 1.5, -915, -540
led_after = (PX + PS * LED[0], PY + PS * LED[1])
fix = f"""<template>
  <style>
    {CSS}
  </style>
  <div id="root" data-composition-id="fix" data-width="1920" data-height="1080">
    <div class="bg"></div>
    <div id="fx-cam" class="cam" data-layout-allow-overflow>
      <img id="fx-plate" class="plate" src="assets/gen/shotB2-last.jpg" alt="" />
      <video id="fx-shot" class="shot clip" src="assets/gen/shotB2.mp4" data-start="0" data-duration="8" data-track-index="1" muted playsinline></video>
      {code_html("fx")}
      {term_html("fx", '<div>⏺ The battery sags when the radio transmits. Power the board from USB, or add a 100 µF capacitor across 3V3 and GND.</div>')}
      {fx_svg("fx")}
    </div>
    {label("fx-l1", "Steady green: <em>fixed</em>", led_after[0] + 6, led_after[1] - 10, 1180, 330)}
  </div>
  <script>
    (function () {{
      {JS}
      place("fx-code", {q(RIGHT)});
      place("fx-term", {q(LEFT)});
      const tl = gsap.timeline({{ paused: true }});
      // The code keeps blinking until the plug goes in at 4.3 s, then the LED stays on.
      blinkCode(tl, "#fx-glow", 0, 4.6);
      tl.set("#fx-glow", {{ opacity: 1 }}, 4.6);
      tl.fromTo("#fx-cam", {{ opacity: 0, filter: "blur(10px)" }}, {{ opacity: 1, filter: "blur(0px)", duration: 0.25, ease: "power2.out" }}, 0);
      // Punch in on the board once the hand has left it, so the steady LED is large.
      tl.fromTo("#fx-cam", {{ x: 0, y: 0, scale: 1, transformOrigin: "0 0" }}, {{ x: {PX}, y: {PY}, scale: {PS}, transformOrigin: "0 0", duration: 1.1, ease: "power2.inOut", immediateRender: false }}, 4.8);
      showLabel(tl, "fx-l1", 6.0, 2.0);
      tl.to("#fx-cam", {{ opacity: 0, filter: "blur(10px)", duration: 0.2, ease: "power1.in" }}, 8.35);
      tl.to({{}}, {{ duration: 0.01 }}, 8.59);
      window.__timelines["fix"] = tl;
    }})();
  </script>
</template>
"""

open(f"{OUT}/compositions/setup.html", "w").write(setup)
open(f"{OUT}/compositions/fix.html", "w").write(fix)
print("fix label target", led_after)
