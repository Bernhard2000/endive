#!/usr/bin/env python3
"""Markdown summary of the engine A/B campaign (results/<workload>/results.<metric>.json)
and the JMH forks (results/jmh/run<i>-<A|B>.json)."""
import json, random, statistics, sys
from pathlib import Path

R = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).parent / "results")
ORDER = ["lua-base", "sqlite-base", "quickjs-base", "lua-split", "sqlite-split", "quickjs-split"]


def boot(vals, n=10000, seed=42):
    rng = random.Random(seed)
    out = sorted(statistics.median(rng.choices(vals, k=len(vals))) for _ in range(n))
    return out[int(0.025 * n)], out[int(0.975 * n)]


def row(path):
    d = json.loads(path.read_text())
    a, b = d["runs_ns"]["upstream"], d["runs_ns"]["pr"]
    ma, mb = statistics.median(a) / 1e6, statistics.median(b) / 1e6
    ratios = [y / x for x, y in zip(a, b)]
    med = statistics.median(ratios)
    lo, hi = boot(ratios)
    sig = "yes" if lo > 1 or hi < 1 else "no"
    return len(a), ma, mb, (med - 1) * 100, (lo - 1) * 100, (hi - 1) * 100, sig


print("## Engine A/B (fresh JVM per sample; paired per-round ratio pr/upstream)\n")
for metric, title in (("steady", "steady-state execution (median of in-process reps)"),
                      ("first", "first execution in a fresh JVM"),
                      ("compile", "parse + runtime compile")):
    print(f"### {title}\n")
    print("| workload | n | upstream ms | PR ms | Δ (paired median) | 95 % CI | significant |")
    print("| --- | ---: | ---: | ---: | ---: | --- | --- |")
    names = [w for w in ORDER if (R / w / f"results.{metric}.json").exists()]
    for w in names:
        n, ma, mb, d, lo, hi, sig = row(R / w / f"results.{metric}.json")
        print(f"| {w} | {n} | {ma:,.1f} | {mb:,.1f} | {d:+.1f} % | [{lo:+.1f} %, {hi:+.1f} %] | {sig} |")
    print()

jmh = sorted(R.glob("jmh*/run*.json"))
if jmh:
    scores = {}
    for f in jmh:
        engine = f.stem.split("-")[1]
        try:
            results = json.loads(f.read_text())
        except ValueError:
            continue  # run still in progress
        for r in results:
            params = ",".join(f"{k}={v}" for k, v in sorted((r.get("params") or {}).items()))
            key = (r["benchmark"].replace("run.endive.bench.", ""), params, r["mode"],
                   r["primaryMetric"]["scoreUnit"])
            scores.setdefault(key, {}).setdefault(engine, []).append(r["primaryMetric"]["score"])
    print("## Endive JMH (3 forks per engine, order A B B A A B)\n")
    print("| benchmark | params | mode | unit | upstream (mean of forks) | PR | PR vs upstream | fork spread A / B |")
    print("| --- | --- | --- | --- | ---: | ---: | ---: | --- |")
    for (bench, params, mode, unit), e in sorted(scores.items()):
        a, b = e.get("A", []), e.get("B", [])
        if not a or not b:
            continue
        ma, mb = statistics.mean(a), statistics.mean(b)
        better = (mb / ma - 1) * 100 if mode == "thrpt" else (ma / mb - 1) * 100
        spread = lambda v: f"{(max(v) - min(v)) / statistics.mean(v) * 100:.1f} %"
        print(f"| {bench} | {params} | {mode} | {unit} | {ma:,.4g} | {mb:,.4g} | "
              f"{better:+.1f} % {'faster' if better > 0 else 'slower'} | {spread(a)} / {spread(b)} |")
    print()
