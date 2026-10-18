#!/usr/bin/env python3
"""Paired per-round ratios arm/upstream of run.sh's CSV, bootstrap 95 % CI (seed 42)."""
import csv, random, statistics, sys
from collections import defaultdict
rows = list(csv.DictReader(open(sys.argv[1])))
col = sys.argv[2] if len(sys.argv) > 2 else "first_us"
data = defaultdict(dict)
for r in rows:
    data[(r["module"], r["round"])][r["arm"]] = float(r[col])
modules = sorted({m for m, _ in data})
arms = sorted({a for v in data.values() for a in v} - {"upstream"})
def boot(v, n=10000):
    rng = random.Random(42)
    s = sorted(statistics.median(rng.choices(v, k=len(v))) for _ in range(n))
    return s[int(.025 * n)], s[int(.975 * n)]
print(f"| module | arm | n | upstream ms | arm ms | Δ ({col}) | 95 % CI |")
print("| --- | --- | ---: | ---: | ---: | ---: | --- |")
for m in modules:
    rounds = [v for (mm, _), v in data.items() if mm == m]
    for a in arms:
        pairs = [(v["upstream"], v[a]) for v in rounds if "upstream" in v and a in v]
        ratios = [b / x for x, b in pairs]
        lo, hi = boot(ratios)
        print(f"| {m} | {a} | {len(pairs)} | {statistics.median(x for x, _ in pairs)/1000:,.0f} "
              f"| {statistics.median(b for _, b in pairs)/1000:,.0f} "
              f"| {(statistics.median(ratios)-1)*100:+.1f} % | [{(lo-1)*100:+.1f}, {(hi-1)*100:+.1f}] |")
