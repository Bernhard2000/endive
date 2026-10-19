#!/usr/bin/env python3
"""Figures for workload-ab-2026-10-18.md, from results/<workload>/results.<metric>.json and
bisect-rustvm.txt. Writes plots/*.png and plots/*.svg next to this script."""
import json, random, re, statistics
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D

HERE = Path(__file__).parent
R = HERE / "results"
RX = HERE / "results-excluded"   # synthetic cells, kept for the rustvm-base regression only
OUT = HERE / "plots"
OUT.mkdir(exist_ok=True)

# Reference palette (dataviz skill, light mode). Polarity: blue = fork faster, red = fork
# slower, gray = no significant difference. Identity: blue = fork, orange = upstream.
SURFACE, INK, INK2, MUTED, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#8a8985", "#e4e3df"
FASTER, SLOWER, NEUTRAL = "#2a78d6", "#e34948", "#a3a29d"
FORK, UP = "#2a78d6", "#eb6834"
BIMODAL = set()   # awfy-lua-split-NBody was unstable; replaced by the longer NBody-long cell

LUA_B = ["Richards", "DeltaBlue", "Json", "NBody", "CD"]
JS_B = ["Richards", "DeltaBlue", "Json", "NBody", "Bounce", "CD"]
FAMILIES = [
    ("Earlier anchors", ["lua-base", "lua-split", "sqlite-split", "quickjs-split", "python-split"]),
    ("Emulators", ["binjgb-base", "binjgb-split", "i8080-base", "i8080-split", "z80-base",
                   "z80-split", "genesis-base", "genesis-split"]),
    ("SQLite applications", ["sqlite-app-split", "speedtest1-split"]),
    ("AWFY on Lua", [f"awfy-lua-{k}-{b}" + ("-long" if (k, b) == ("split", "NBody") else "")
                     for b in LUA_B for k in ("unsplit", "split")]),
    ("AWFY on QuickJS", [f"awfy-js-{k}-{b}" for b in JS_B for k in ("unsplit", "split")]),
]


def label(w):
    m = re.match(r"awfy-(lua|js)-(unsplit|split)-(\w+)(-long)?$", w)
    if not m:
        return w
    return f"{m.group(3)}, {m.group(2)}" + (" (10× longer)" if m.group(4) else "")


METRICS = [("steady", "Steady state"), ("first", "First execution"),
           ("compile", "Parse + runtime compile")]

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "axes.edgecolor": GRID, "axes.labelcolor": INK2, "xtick.color": INK2, "ytick.color": INK,
    "text.color": INK, "font.size": 9, "axes.titlesize": 10, "axes.titleweight": "bold",
    "axes.spines.top": False, "axes.spines.right": False, "axes.spines.left": False,
    "xtick.major.size": 0, "ytick.major.size": 0,
})


def boot(vals, n=10000, seed=42):
    rng = random.Random(seed)
    out = sorted(statistics.median(rng.choices(vals, k=len(vals))) for _ in range(n))
    return out[int(0.025 * n)], out[int(0.975 * n)]


def runs(w, metric):
    f = R / w / f"results.{metric}.json"
    d = json.loads((f if f.exists() else RX / w / f"results.{metric}.json").read_text())["runs_ns"]
    return d["up"], d["fork"]


def delta(w, metric):
    a, b = runs(w, metric)
    ratios = [y / x for x, y in zip(a, b)]
    lo, hi = boot(ratios)
    return (statistics.median(ratios) - 1) * 100, (lo - 1) * 100, (hi - 1) * 100


def verdict_color(lo, hi):
    return FASTER if hi < 0 else SLOWER if lo > 0 else NEUTRAL


def rows():
    """(y positions, labels, header positions) for the grouped workload axis."""
    ys, labels, headers, y = {}, [], [], 0
    for fam, ws in FAMILIES:
        headers.append((y, fam))
        y += 1
        for w in ws:
            ys[w] = y
            labels.append((y, w))
            y += 1
        y += 0.4
    return ys, labels, headers, y


def forest(ax, metric, show_labels=True, annotate=(), xlim=(-30, 30)):
    ys, labels, headers, ymax = rows()
    ax.set_xlim(*xlim)
    ax.axvline(0, color=MUTED, lw=1, zorder=1)
    for y, w in labels:
        d, lo, hi = delta(w, metric)
        c = verdict_color(lo, hi)
        ax.plot([lo, hi], [y, y], color=c, lw=2, solid_capstyle="round", zorder=2)
        hollow = w in BIMODAL and metric == "steady"
        ax.plot(d, y, "o", ms=7, mfc=SURFACE if hollow else c, mec=c if hollow else SURFACE,
                mew=2, zorder=3)
        xmax = ax.get_xlim()[1]
        if hi > xmax:
            ax.annotate(f"CI to {hi:+.0f} % ▸", (xmax, y), xytext=(0, 7), textcoords="offset points",
                        ha="right", va="bottom", color=INK2, fontsize=7)
        if w in annotate:
            ax.annotate(f"{d:+.1f} %", (hi, y), xytext=(6, 0), textcoords="offset points",
                        va="center", ha="left", color=INK2, fontsize=8)
    ax.set_ylim(ymax, -0.8)
    ax.set_yticks([y for y, _ in labels])
    ax.set_yticklabels([label(w) for _, w in labels] if show_labels else [])
    for y, fam in headers:
        if show_labels:
            ax.text(-0.02, y, fam, transform=ax.get_yaxis_transform(), ha="right", va="center",
                    fontweight="bold", color=INK, fontsize=9)
    ax.grid(axis="x", color=GRID, lw=1)
    ax.set_axisbelow(True)
    ax.xaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f"{v:+.0f} %"))


def verdict_legend(fig, loc, bimodal=True):
    h = [Line2D([], [], color=FASTER, lw=2, marker="o", ms=7, mec=SURFACE, mew=2,
                label="fork faster (95 % CI below 0)"),
         Line2D([], [], color=SLOWER, lw=2, marker="o", ms=7, mec=SURFACE, mew=2,
                label="fork slower (95 % CI above 0)"),
         Line2D([], [], color=NEUTRAL, lw=2, marker="o", ms=7, mec=SURFACE, mew=2,
                label="no significant difference")]
    if bimodal:
        h.append(Line2D([], [], color=MUTED, lw=0, marker="o", ms=7, mfc=SURFACE, mec=MUTED,
                        mew=2, label="unstable cell (undetermined)"))
    fig.legend(handles=h, loc="lower center", bbox_to_anchor=(0.5, 0.0), ncol=len(h),
              frameon=False, fontsize=8, handlelength=2.6)


def save(fig, name):
    for ext in ("png", "svg"):
        fig.savefig(OUT / f"{name}.{ext}", dpi=160, bbox_inches="tight")
    plt.close(fig)


ANNOTATE = ("sqlite-split", "awfy-js-split-DeltaBlue")

# 1. Forest plot of the steady-state change -------------------------------------------
fig, ax = plt.subplots(figsize=(7.2, 11.5))
forest(ax, "steady", annotate=ANNOTATE)
ax.set_xlabel("steady-state time, fork vs upstream (paired median, 95 % bootstrap CI)")
ax.set_title("Fork 44f68a6f against upstream 1580f534: steady state, 12 rounds per cell",
             loc="left", x=-0.42)
verdict_legend(fig, "lower left", bimodal=bool(BIMODAL))
save(fig, "steady-forest")

# 2. Small multiples: steady, first execution, compile ---------------------------------
fig, axes = plt.subplots(1, 3, figsize=(11, 11.5), sharey=True,
                         gridspec_kw={"width_ratios": [1, 1, 0.9]})
for i, (ax, (metric, title)) in enumerate(zip(axes, METRICS)):
    forest(ax, metric, show_labels=(i == 0),
           xlim=(-30, 30) if metric != "compile" else (-12, 30))
    ax.set_title(title, loc="left")
    ax.set_xticks([-20, 0, 20] if metric != "compile" else [-10, 0, 10, 20])
axes[0].set_yticks([y for y, _ in rows()[1]])
axes[0].set_yticklabels([label(w) for _, w in rows()[1]])
for ax in axes:
    ax.set_xlabel("fork vs upstream", color=INK2)
verdict_legend(fig, None, bimodal=bool(BIMODAL))
fig.subplots_adjust(wspace=0.12, bottom=0.09)
save(fig, "metrics-forest")

# 3. Unsplit and split, both engines, absolute steady time ----------------------------
panels = [
    ("Corpus workloads", [(n, f"{n}-base", f"{n}-split") for n in ("lua", "binjgb", "i8080", "z80", "genesis")]),
    # Lua NBody is left out: its split cell runs 10× the iterations of the unsplit one
    ("AWFY (Lua, then QuickJS)", [(f"Lua {b}", f"awfy-lua-unsplit-{b}", f"awfy-lua-split-{b}")
                                  for b in LUA_B if b != "NBody"]
     + [(f"JS {b}", f"awfy-js-unsplit-{b}", f"awfy-js-split-{b}") for b in JS_B]),
]
fig, axes = plt.subplots(1, 2, figsize=(11, 7.2), sharex=True,
                         gridspec_kw={"width_ratios": [1, 1]})
for ax, (title, pairs) in zip(axes, panels):
    labels, y = [], 0
    for name, base, split in pairs:
        for w, kind in ((base, "unsplit"), (split, "split")):
            a, b = runs(w, "steady")
            ma, mb = statistics.median(a) / 1e9, statistics.median(b) / 1e9
            ax.plot([ma, mb], [y, y], color=GRID, lw=2, zorder=1, solid_capstyle="round")
            ax.plot(mb, y, "o", ms=7, color=FORK, mec=SURFACE, mew=1.5, zorder=3)
            ax.plot(ma, y, "o", ms=10, mfc="none", mec=UP, mew=2, zorder=4)
            labels.append((y, f"{name}  {kind}"))
            y += 1
        y += 0.4
    ax.set_xscale("log")
    ax.set_xlim(0.1, 60)
    ax.set_xticks([0.1, 0.3, 1, 3, 10, 30])
    ax.xaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f"{v:g} s"))
    ax.xaxis.set_minor_locator(matplotlib.ticker.NullLocator())
    ax.set_yticks([p for p, _ in labels])
    ax.set_yticklabels([t for _, t in labels], fontsize=8)
    ax.set_ylim(y - 0.4, -0.8)
    ax.grid(axis="x", color=GRID, lw=1)
    ax.set_axisbelow(True)
    ax.set_title(title, loc="left")
    ax.set_xlabel("steady time per run (median of 12 fresh JVMs, log scale)")
fig.suptitle("Splitting moves the big step; the fork adds a smaller one on top",
             x=0.02, ha="left", fontweight="bold", fontsize=10)
fig.legend(handles=[Line2D([], [], lw=0, marker="o", ms=10, mfc="none", mec=UP, mew=2,
                           label="upstream 1580f534"),
                    Line2D([], [], lw=0, marker="o", ms=7, color=FORK, mec=SURFACE, mew=1.5,
                           label="fork 44f68a6f")],
           loc="lower center", bbox_to_anchor=(0.5, -0.04), ncol=2, frameon=False, fontsize=8)
fig.tight_layout()
save(fig, "base-vs-split")

# 4. rustvm-base bisection ---------------------------------------------------------------
order = [("f5225324", "f5225324\nfusion"), ("b4d973e1", "b4d973e1\nABI repair"),
         ("3ba75b28", "3ba75b28\nPR branch"), ("82f39301", "82f39301\nswitch encoding"),
         ("5a02a7dd", "5a02a7dd\nfusion ext."), ("exp-tableswitch", "44f68a6f +\nforced tableswitch")]
samples = {}
for line in (HERE / "bisect-rustvm.txt").read_text().splitlines():
    m = re.match(r"(\S+)\s+up=([\d ]+) cand=([\d ]+)$", line)
    if m:
        up = [int(x) for x in m.group(2).split()]
        cand = [int(x) for x in m.group(3).split()]
        samples.setdefault(m.group(1), []).extend(c / statistics.median(up) for c in cand)
a, b = runs("rustvm-base", "steady")
samples["44f68a6f"] = [y / statistics.median(a) for y in b]
order.insert(5, ("44f68a6f", "44f68a6f\n(campaign)"))
fig, ax = plt.subplots(figsize=(9.6, 3.9))
ax.axhline(0, color=MUTED, lw=1, zorder=1)
for i, (key, _) in enumerate(order):
    vals = [(v - 1) * 100 for v in samples[key]]
    med = statistics.median(vals)
    c = SLOWER if med > 5 else NEUTRAL
    jitter = [i + (j - (len(vals) - 1) / 2) * min(0.05, 0.5 / len(vals)) for j in range(len(vals))]
    ax.plot(jitter, vals, "o", ms=6, color=c, mec=SURFACE, mew=1.5, zorder=3, alpha=0.9)
    ax.plot([i - 0.28, i + 0.28], [med, med], color=INK, lw=2, zorder=4, solid_capstyle="round")
    ax.annotate("±0 %" if abs(med) < 0.5 else f"{med:+.0f} %", (i + 0.3, med), xytext=(4, 0), textcoords="offset points",
                va="center", color=INK2, fontsize=8)
ax.set_xticks(range(len(order)))
ax.set_xticklabels([t for _, t in order], fontsize=8)
ax.set_xlim(-0.5, len(order) - 0.2)
ax.yaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f"{v:+.0f} %"))
ax.grid(axis="y", color=GRID, lw=1)
ax.set_axisbelow(True)
ax.tick_params(axis="y", colors=INK2)
ax.set_ylabel("rustvm-base time vs upstream median", color=INK2)
ax.set_title("rustvm-base (synthetic): the slowdown arrives with 82f39301 and leaves with tableswitch",
             loc="left")
ax.legend(handles=[Line2D([], [], lw=0, marker="o", ms=6, color=SLOWER, label="regressed (median > +5 %)"),
                   Line2D([], [], lw=0, marker="o", ms=6, color=NEUTRAL, label="≈ upstream"),
                   Line2D([], [], color=INK, lw=2, label="median")],
          loc="upper left", frameon=False, fontsize=8, ncol=3)
ax.set_ylim(-8, 34)
save(fig, "rustvm-bisect")

if BIMODAL:
    # 5. Unstable cells, per sample -----------------------------------------------------------
    cells = sorted(BIMODAL)
    fig, axes = plt.subplots(1, len(cells), figsize=(max(6.4, 4.2 * len(cells)), 3.2), squeeze=False)
    for ax, w in zip(axes[0], cells):
        a, b = runs(w, "steady")
        scale, unit = (1e9, "s") if statistics.median(a) > 2e9 else (1e6, "ms")
        for j, (vals, c) in enumerate(((a, UP), (b, FORK))):
            xs = [j + (k - (len(vals) - 1) / 2) * 0.03 for k in range(len(vals))]
            ax.plot(xs, [v / scale for v in vals], "o", ms=7, color=c, mec=SURFACE, mew=1.5)
        ax.set_xticks([0, 1])
        ax.set_xticklabels(["upstream", "fork"])
        ax.set_xlim(-0.6, 1.6)
        ax.grid(axis="y", color=GRID, lw=1)
        ax.set_axisbelow(True)
        ax.tick_params(axis="y", colors=INK2)
        ax.set_ylabel(f"steady time per run ({unit})", color=INK2)
        ax.set_title(f"{w} (one dot per JVM)", loc="left")
    fig.suptitle("Unstable cell: JVMs land in different JIT states in both arms",
                 x=0.02, ha="left", fontweight="bold", fontsize=10)
    fig.tight_layout()
    save(fig, "bimodal-cells")
print("wrote", sorted(p.name for p in OUT.iterdir()))
