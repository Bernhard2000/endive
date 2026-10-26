#!/usr/bin/env python3
"""Figure of the static fusion effect, from method-sizes-upstream-vs-pr.txt and
helper-calls-upstream-vs-pr.txt. Writes plots/fusion-static.png and .svg next to this script.

Revisions: out-up = upstream 1580f534, out-pr1 = original fusion e4105edf, out-pr = with the
extensions 3ba75b28 (see README.md). The ref_conditions fixture is left out: it is a synthetic
GC test module, and its -17 % would set the scale for six real modules."""
import re
from collections import defaultdict
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Patch

HERE = Path(__file__).parent
OUT = HERE / "plots"
OUT.mkdir(exist_ok=True)

# Reference palette (dataviz skill, light mode), as in ../workload-ab-2026-10-18/plot.py.
# Identity: slot 1 blue = original fusion, slot 2 orange = the extensions on top of it.
SURFACE, INK, INK2, MUTED, GRID = "#fcfcfb", "#0b0b0b", "#52514e", "#8a8985", "#e4e3df"
FUSION, EXT = "#2a78d6", "#eb6834"

MODULES = [
    ("basic.dotnet.wasm", ".NET"),
    ("quickjs-provider.javy-dynamic.wasm", "QuickJS (Javy dynamic)"),
    ("javy-demo.js.javy.wasm", "Javy demo"),
    ("main.go.wasm", "Go"),
    ("greet-wasi.rs.wasm", "Rust (greet-wasi)"),
    ("code.wasm", "code.wasm"),
]

plt.rcParams.update({
    "figure.facecolor": SURFACE, "axes.facecolor": SURFACE, "savefig.facecolor": SURFACE,
    "axes.edgecolor": GRID, "axes.labelcolor": INK2, "xtick.color": INK2, "ytick.color": INK,
    "text.color": INK, "font.size": 9, "axes.titlesize": 10, "axes.titleweight": "bold",
    "axes.spines.top": False, "axes.spines.right": False, "axes.spines.left": False,
    "xtick.major.size": 0, "ytick.major.size": 0,
})


def bytecode():
    sizes = {}
    for line in (HERE / "method-sizes-upstream-vs-pr.txt").read_text().splitlines():
        m = re.match(r"(\S+)\s+\| out-up: func=\s*(\d+) huge=\s*(\d+) \| out-pr1: func=\s*(\d+) "
                     r"huge=\s*(\d+) .*\| out-pr: func=\s*(\d+) huge=\s*(\d+)", line)
        if m:
            up, _, pr1, _, pr, _ = map(int, m.groups()[1:])
            huge = (int(m.group(3)), int(m.group(7)))
            sizes[m.group(1)] = (up, pr1, pr, huge)
    return sizes


def helper_calls():
    """Comparison and null-test helper invokestatics; JDK Long.compareUnsigned excluded."""
    total, current = defaultdict(int), None
    for line in (HERE / "helper-calls-upstream-vs-pr.txt").read_text().splitlines():
        m = re.match(r"== (\S+) (\S+)", line)
        if m:
            current = m.groups()
            continue
        m = re.match(r"\s*(\d+) (\S+)", line)
        if m and "Long.compareUnsigned" not in m.group(2):
            total[current] += int(m.group(1))
    return {mod: (total[(mod, "out-up")], total[(mod, "out-pr1")], total[(mod, "out-pr")])
            for mod, _ in MODULES}


def panel(ax, values, title, xlabel, xmax, note=None):
    """values: name -> (upstream, after fusion, after extensions); bars show % removed."""
    for y, (mod, name) in enumerate(MODULES):
        up, pr1, pr = values[mod]
        first, total = (1 - pr1 / up) * 100, (1 - pr / up) * 100
        # 2 px surface gap between the two segments, as between adjacent fills.
        ax.barh(y, first, height=0.56, color=FUSION, edgecolor=SURFACE, linewidth=1.5)
        ax.barh(y, total - first, left=first, height=0.56, color=EXT, edgecolor=SURFACE,
                linewidth=1.5)
        ax.annotate(f"−{total:.1f} %" if total < 10 else f"−{total:.0f} %", (total, y),
                    xytext=(5, 0), textcoords="offset points", va="center", ha="left",
                    color=INK, fontsize=8.5)
        if note and mod in note:
            ax.text(0.03, y + 0.44, note[mod], va="center", ha="left", color=INK2, fontsize=7)
    ax.set_xlim(0, xmax)
    ax.set_ylim(len(MODULES) - 0.4, -0.6)
    ax.set_yticks(range(len(MODULES)))
    ax.set_yticklabels([name for _, name in MODULES])
    ax.grid(axis="x", color=GRID, lw=1)
    ax.set_axisbelow(True)
    ax.xaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda v, _: f"{v:.0f} %"))
    ax.set_title(title, loc="left")
    ax.set_xlabel(xlabel)


sizes = bytecode()
fig, (left, right) = plt.subplots(1, 2, figsize=(10, 3.9), sharey=True,
                                  gridspec_kw={"width_ratios": [1, 1.25], "wspace": 0.16})
huge = {mod: (f"methods > 8000 B: {h[0]} → {h[1]}" if h[0] else None)
        for mod, (_, _, _, h) in sizes.items()}
panel(left, {m: s[:3] for m, s in sizes.items()}, "func_* bytecode removed",
      "share of upstream bytecode", 5, {m: t for m, t in huge.items() if t})
panel(right, helper_calls(), "Comparison helper calls removed",
      "share of upstream helper call sites (static)", 100)
left.set_xticks([0, 1, 2, 3, 4, 5])
right.set_xticks([0, 25, 50, 75, 100])
fig.suptitle("Fusion against upstream 1580f534: static counts per module", x=0.01, ha="left",
             fontweight="bold", fontsize=10, y=1.06)
fig.legend(handles=[Patch(color=FUSION, label="original fusion (e4105edf)"),
                    Patch(color=EXT, label="+ extensions (3ba75b28)")],
           loc="upper left", bbox_to_anchor=(0.005, 1.0), ncol=2, frameon=False, fontsize=8.5)
for ext in ("png", "svg"):
    fig.savefig(OUT / f"fusion-static.{ext}", dpi=160, bbox_inches="tight")
print("wrote", sorted(p.name for p in OUT.iterdir()))
