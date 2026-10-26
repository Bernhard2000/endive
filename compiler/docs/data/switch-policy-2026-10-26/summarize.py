#!/usr/bin/env python3
"""Paired per-round ratios for the switch-policy remeasure (results/<workload>/<arm>.jsonl):
median ratio with a bootstrap 95 % CI, plus a guest-output equality check over all samples."""
import json, random, re, statistics, sys
from pathlib import Path

R = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).parent / "results")
PAIRS = [("fork21", "up21", "fork vs upstream, Java 21")]
METRICS = [("exec_ms_median", "steady"), ("first_exec_ms", "first"), ("compile_ms", "compile")]
TIMING = [(re.compile(r"\d+(\.\d+)?\s*(us|ms|s)\b"), "<t>"), (re.compile(r"_ns=\d+"), "_ns=<t>")]


def boot(vals, n=10000, seed=42):
    rng = random.Random(seed)
    out = sorted(statistics.median(rng.choices(vals, k=len(vals))) for _ in range(n))
    return out[int(0.025 * n)], out[int(0.975 * n)]


def load(d, arm):
    return {r["round"]: r for r in map(json.loads, (d / f"{arm}.jsonl").read_text().splitlines())}


def guest(path):
    text = "\n".join(l for l in path.read_text(errors="replace").splitlines() if not l.startswith("{"))
    for rx, sub in TIMING:
        text = rx.sub(sub, text)
    return text


for metric, label in METRICS:
    print(f"\n### {label} ({metric}), Δ = median paired ratio − 1, 95 % bootstrap CI\n")
    print("| workload | " + " | ".join(p[2] for p in PAIRS) + " |")
    print("|---|" + "---|" * len(PAIRS))
    for d in sorted(p for p in R.iterdir() if p.is_dir()):
        cells = []
        for a, b, _ in PAIRS:
            try:
                A, Bv = load(d, a), load(d, b)
            except FileNotFoundError:
                cells.append("–")
                continue
            rounds = sorted(set(A) & set(Bv))
            ratios = [float(A[r][metric]) / float(Bv[r][metric]) for r in rounds]
            if len(ratios) < 3:
                cells.append(f"n={len(ratios)}")
                continue
            lo, hi = boot(ratios)
            m = statistics.median(ratios)
            sig = "**" if lo > 1 or hi < 1 else ""
            cells.append(f"{sig}{(m - 1) * 100:+.1f} %{sig} [{(lo - 1) * 100:+.1f}, {(hi - 1) * 100:+.1f}] n={len(ratios)}")
        print(f"| {d.name} | " + " | ".join(cells) + " |")

print("\n### medians (ms) and guest output check\n")
for d in sorted(p for p in R.iterdir() if p.is_dir()):
    meds = []
    for arm in ("up21", "fork21"):
        try:
            rows = load(d, arm).values()
            meds.append(f"{arm} {statistics.median(float(r['exec_ms_median']) for r in rows):.0f}")
        except FileNotFoundError:
            pass
    outs = {guest(p) for p in (d / "outputs").glob("*.out")}
    print(f"- {d.name}: {', '.join(meds)}; distinct guest outputs: {len(outs)}")
