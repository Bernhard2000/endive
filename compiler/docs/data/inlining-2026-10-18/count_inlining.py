#!/usr/bin/env python3
"""Counts C2 inlining decisions for helper call sites in -XX:+PrintInlining output.

    count_inlining.py LOG [LOG ...]

Helpers: run.endive.runtime.OpcodeImpl::*, the generated *Shaded::* copies and the JDK
methods the compiler calls for comparisons and conversions. Prints, per log, the number of
call sites per decision (the text after the method size, e.g. "inline (hot)",
"size > DesiredMethodLimit") and, for the refusals, which helpers were refused.
Only depth-0 call sites (the generated function's own calls) are counted."""
import collections
import re
import sys

LINE = re.compile(r"^(\s*)@ \d+\s+(\S+)::(\S+) \((\d+) bytes\)\s+(.*)$")
HELPER = re.compile(r"(OpcodeImpl|Shaded|java\.lang\.(Integer|Long|Float|Double|Math))$")

for path in sys.argv[1:]:
    decisions = collections.Counter()
    refused = collections.Counter()
    depth0 = None
    for line in open(path, errors="replace"):
        m = LINE.match(line.rstrip("\n"))
        if not m:
            continue
        indent, owner, name, _, decision = m.groups()
        if depth0 is None:
            depth0 = len(indent)
        if len(indent) != depth0 or not HELPER.search(owner):
            continue
        decision = decision.strip()
        decisions[decision] += 1
        if not decision.startswith("inline") and decision != "(intrinsic)":
            refused[f"{owner.rsplit('.', 1)[-1]}::{name}"] += 1
    total = sum(decisions.values())
    print(f"== {path}: {total} helper call sites")
    for d, n in decisions.most_common():
        print(f"  {n:6d}  {d}")
    for k, n in refused.most_common(10):
        print(f"      refused {n:5d}  {k}")
