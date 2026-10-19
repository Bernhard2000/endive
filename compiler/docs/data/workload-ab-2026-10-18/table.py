#!/usr/bin/env python3
"""Markdown results table for workload-ab-2026-10-18.md from results/summary.json."""
import json
from pathlib import Path

HERE = Path(__file__).parent
s = json.loads((HERE / "results" / "summary.json").read_text())
order = json.loads((HERE / "order.json").read_text())
UNSTABLE = {"awfy-lua-split-NBody"}


def pct(m, bold=False):
    t = f"{m['delta_pct']:+.1f} %".replace("-", "−")
    sig = m["significant"] == "yes"
    return f"**{t}**" if bold and sig else t if sig or bold else f"{t} n.s."


print("| workload | n | up ms | fork ms | Δ steady | 95 % CI | Δ first exec | Δ compile |")
print("| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: |")
for w in order:
    st = s[w]["steady"]
    ci = f"[{st['ci_lo_pct']:+.1f}, {st['ci_hi_pct']:+.1f}]".replace("-", "−")
    note = " (unstable)" if w in UNSTABLE else ""
    print(f"| {w} | {st['n']} | {st['up_ms']:,.0f} | {st['fork_ms']:,.0f} | {pct(st, True)}{note} | {ci} "
          f"| {pct(s[w]['first'])} | {pct(s[w]['compile'])} |")
