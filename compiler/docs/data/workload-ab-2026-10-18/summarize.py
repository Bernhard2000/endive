#!/usr/bin/env python3
"""Markdown summary of the extended engine A/B (results/<workload>/results.<metric>.json):
paired per-round ratio fork/up, median with a bootstrap 95 % CI, plus a guest-output
equality check over every saved sample of both arms (abstats.py says what is masked).

Fails on a cell whose samples or outputs are incomplete or differ. --partial-outputs compares
only the rounds whose outputs were kept for both arms and says how many those are (the archived
data directory keeps round 1 only)."""
import argparse, json, statistics, sys
from pathlib import Path

from abstats import CellError, compare_outputs, load_runs, paired_delta

ap = argparse.ArgumentParser()
ap.add_argument("results", nargs="?", default=Path(__file__).parent / "results", type=Path)
ap.add_argument("--partial-outputs", action="store_true")
args = ap.parse_args()
R = args.results


def row(path):
    a, b = load_runs(path)
    d, lo, hi = paired_delta(a, b)
    sig = "yes" if lo > 0 or hi < 0 else "no"
    return len(a), statistics.median(a) / 1e6, statistics.median(b) / 1e6, d, lo, hi, sig


names = sorted(p.name for p in R.iterdir() if (p / "results.steady.json").exists())
order = json.loads((Path(__file__).parent / "order.json").read_text()) \
    if (Path(__file__).parent / "order.json").exists() else names
names = [n for n in order if n in names] + [n for n in names if n not in order]

failures = []
summary = {}
for metric, title in (("steady", "Steady-state execution (median of in-process reps)"),
                      ("first", "First execution in a fresh JVM"),
                      ("compile", "Parse + runtime compile")):
    print(f"### {title}\n")
    print("| workload | n | upstream ms | fork ms | Δ (paired median) | 95 % CI | significant |")
    print("| --- | ---: | ---: | ---: | ---: | --- | --- |")
    for w in names:
        f = R / w / f"results.{metric}.json"
        if not f.exists():
            continue
        try:
            n, ma, mb, d, lo, hi, sig = row(f)
        except CellError as e:
            failures.append(str(e))
            print(f"| {w} | – | – | – | **invalid** | – | – |")
            continue
        summary.setdefault(w, {})[metric] = dict(n=n, up_ms=ma, fork_ms=mb, delta_pct=d,
                                                 ci_lo_pct=lo, ci_hi_pct=hi, significant=sig)
        print(f"| {w} | {n} | {ma:,.1f} | {mb:,.1f} | {d:+.1f} % | [{lo:+.1f} %, {hi:+.1f} %] | {sig} |")
    print()

print("### Guest output identical across arms and samples (run-to-run timings masked)\n")
print("| workload | identical (outputs compared of planned) |")
print("| --- | --- |")
for w in names:
    outputs = R / w / "outputs"
    rounds = summary.get(w, {}).get("steady", {}).get("n")
    if not outputs.is_dir() or rounds is None:
        m = "–"
    else:
        try:
            m = compare_outputs(outputs, rounds, partial=args.partial_outputs)
        except CellError as e:
            failures.append(str(e))
            m = "**NO**"
    summary.setdefault(w, {})["outputs_identical"] = m
    print(f"| {w} | {m} |")
print()
(R / "summary.json").write_text(json.dumps(summary, indent=1) + "\n")
if failures:
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)
