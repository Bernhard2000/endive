#!/usr/bin/env python3
"""Aggregates jdk.ExecutionSample stacks of several recordings per arm.

    jfr_top.py ARM=a.jfr,b.jfr ARM2=c.jfr,...  [--depth N]

Prints per arm the share of samples by top frame's class (self time) and inclusively by Endive method
(every Endive method on the stack counts once per sample), side by side."""
import collections, json, re, subprocess, sys

def samples(path):
    out = subprocess.run(["jfr", "print", "--json", "--events", "jdk.ExecutionSample", path],
                         capture_output=True, text=True, check=True).stdout
    for ev in json.loads(out)["recording"]["events"]:
        frames = ev["values"]["stackTrace"]["frames"] if ev["values"].get("stackTrace") else []
        yield [re.sub(r"\$\$Lambda.*", "$$Lambda",
                      f["method"]["type"]["name"].replace("/", ".")) + "." + f["method"]["name"]
               for f in frames]

arms = {}
for arg in sys.argv[1:]:
    name, files = arg.split("=", 1)
    top, endive = collections.Counter(), collections.Counter()
    n = 0
    for f in files.split(","):
        for st in samples(f):
            if not st:
                continue
            n += 1
            top[st[0].rsplit(".", 1)[0]] += 1
            # inclusive: every Endive or ASM-entry method on the stack, once per sample
            for x in set(st):
                if x.startswith("run.endive") or x.startswith("org.objectweb.asm.MethodWriter.compute"):
                    endive[x] += 1
    arms[name] = (n, top, endive)

for title, idx in (("self (top frame class)", 1), ("inclusive (Endive methods)", 2)):
    print(f"== {title}")
    keys = collections.Counter()
    for n, *c in arms.values():
        for k, v in c[idx - 1].items():
            keys[k] += v / n
    print("  " + " ".join(f"{a:>10s}" for a in arms) + "  ")
    for k, _ in keys.most_common(30):
        print("  " + " ".join(f"{100 * arms[a][idx][k] / arms[a][0]:9.1f}%" for a in arms) + f"  {k}")
print("samples: " + ", ".join(f"{a}={v[0]}" for a, v in arms.items()))
