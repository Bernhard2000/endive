#!/usr/bin/env python3
"""Exercise existing reducer functions without executing their top-level report writer."""
import ast
import json
import sys
import tempfile
from pathlib import Path

root = Path(sys.argv[1])
source = root / "compiler/docs/data/workload-ab-2026-10-18/summarize.py"
nodes = ast.parse(source.read_text()).body
selected = [
    node for node in nodes
    if isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef))
    or isinstance(node, ast.Assign)
    and any(isinstance(t, ast.Name) and t.id == "TIMING" for t in node.targets)
]
namespace = {}
exec(compile(ast.Module(body=selected, type_ignores=[]), str(source), "exec"), namespace)

with tempfile.TemporaryDirectory() as directory:
    base = Path(directory)
    up, fork = base / "up.out", base / "fork.out"
    up.write_text('{"answer":1}\n')
    fork.write_text('{"answer":2}\n')
    print("Different JSON results normalize equal:", namespace["normalized"](up) == namespace["normalized"](fork))
    up.write_text("result=1 ms\n")
    fork.write_text("result=2 ms\n")
    print("Different timing-shaped results normalize equal:", namespace["normalized"](up) == namespace["normalized"](fork))
    path = base / "unequal.json"
    path.write_text(json.dumps({"runs_ns": {"up": [100, 100, 100], "fork": [90]}}))
    print("3 upstream / 1 fork samples accepted as row:", namespace["row"](path))
    output = base / "cell" / "outputs"
    output.mkdir(parents=True)
    (output / "up.r1.out").write_text("answer=1\n")
    namespace["R"] = base
    print("Missing fork output accepted as:", namespace["outputs_match"]("cell"))

actual = root / "compiler/docs/data/workload-ab-2026-10-18/results"
files = list(actual.glob("*/results.*.json"))
invalid = []
for path in files:
    data = json.loads(path.read_text())["runs_ns"]
    a, b = data["up"], data["fork"]
    if not a or len(a) != len(b) or any(x <= 0 for x in a + b):
        invalid.append(str(path))
print("Existing result arrays checked:", len(files), "; invalid lengths/nonpositive samples:", len(invalid))
outputs = list(actual.glob("*/outputs/*.out"))
print("Existing archived guest output files:", len(outputs))
