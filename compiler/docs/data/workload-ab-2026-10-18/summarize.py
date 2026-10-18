#!/usr/bin/env python3
"""Markdown summary of the extended engine A/B (results/<workload>/results.<metric>.json):
paired per-round ratio fork/up, median with a bootstrap 95 % CI, plus a guest-output
equality check (timing numbers masked) over every sample of both arms."""
import json, random, re, statistics, sys
from pathlib import Path

R = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).parent / "results")
TIMING = [
    (re.compile(r"\d+(\.\d+)?\s*(us|ms|s)\b"), "<t>"),
    (re.compile(r"_ns=\d+"), "_ns=<t>"),
]


def boot(vals, n=10000, seed=42):
    rng = random.Random(seed)
    out = sorted(statistics.median(rng.choices(vals, k=len(vals))) for _ in range(n))
    return out[int(0.025 * n)], out[int(0.975 * n)]


def row(path):
    d = json.loads(path.read_text())
    a, b = d["runs_ns"]["up"], d["runs_ns"]["fork"]
    ratios = [y / x for x, y in zip(a, b)]
    lo, hi = boot(ratios)
    sig = "yes" if lo > 1 or hi < 1 else "no"
    return (len(a), statistics.median(a) / 1e6, statistics.median(b) / 1e6,
            (statistics.median(ratios) - 1) * 100, (lo - 1) * 100, (hi - 1) * 100, sig)


def normalized(p):
    lines = []
    for line in p.read_text(errors="replace").splitlines():
        if line.startswith("{"):
            continue
        for rx, rep in TIMING:
            line = rx.sub(rep, line)
        lines.append(line)
    return "\n".join(lines)


def outputs_match(w):
    outs = sorted((R / w / "outputs").glob("*.out"))
    if not outs:
        return "–"
    variants = {normalized(p) for p in outs}
    return f"yes ({len(outs)})" if len(variants) == 1 else f"**NO** ({len(variants)} variants)"


names = sorted(p.name for p in R.iterdir() if (p / "results.steady.json").exists())
order = json.loads((Path(__file__).parent / "order.json").read_text()) \
    if (Path(__file__).parent / "order.json").exists() else names
names = [n for n in order if n in names] + [n for n in names if n not in order]

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
        n, ma, mb, d, lo, hi, sig = row(f)
        summary.setdefault(w, {})[metric] = dict(n=n, up_ms=ma, fork_ms=mb, delta_pct=d,
                                                 ci_lo_pct=lo, ci_hi_pct=hi, significant=sig)
        print(f"| {w} | {n} | {ma:,.1f} | {mb:,.1f} | {d:+.1f} % | [{lo:+.1f} %, {hi:+.1f} %] | {sig} |")
    print()

print("### Guest output identical across arms and samples (timings masked)\n")
print("| workload | identical (samples) |")
print("| --- | --- |")
for w in names:
    m = outputs_match(w)
    summary.setdefault(w, {})["outputs_identical"] = m
    print(f"| {w} | {m} |")
print()
(R / "summary.json").write_text(json.dumps(summary, indent=1) + "\n")
