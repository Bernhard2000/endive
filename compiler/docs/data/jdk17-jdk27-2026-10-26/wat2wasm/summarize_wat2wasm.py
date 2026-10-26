#!/usr/bin/env python3
"""Per-arm medians of Wat2WasmStress iteration times (results/<arm>.r<round>.log).
Iterations 0-1 are warm-up and left out; every round's hashes must agree across all arms."""
import re, statistics, sys
from collections import defaultdict
from pathlib import Path

R = Path(sys.argv[1] if len(sys.argv) > 1 else Path(__file__).parent / "results")
LINE = re.compile(r"iter (\d+) (\S+) ([0-9a-f]+) len=\d+ (\d+) ms")
times, hashes, fails = defaultdict(lambda: defaultdict(list)), defaultdict(set), {}
for log in sorted(R.glob("*.r*.log")):
    arm = log.name.split(".r")[0]
    text = log.read_text()
    fails[log.name] = re.search(r"failures=(\d+)", text).group(1)
    for it, name, h, ms in LINE.findall(text):
        hashes[name].add(h)
        if int(it) >= 2:
            times[name][arm].append(int(ms))
print("| input | " + " | ".join(sorted(next(iter(times.values())))) + " |")
print("|---|" + "---|" * len(next(iter(times.values()))))
for name, arms in times.items():
    print(f"| {name} | " + " | ".join(f"{statistics.median(v):.0f} ms (n={len(v)})" for _, v in sorted(arms.items())) + " |")
print()
print("distinct output hashes per input:", {k: len(v) for k, v in hashes.items()})
print("failures per log:", "all 0" if set(fails.values()) == {"0"} else fails)
