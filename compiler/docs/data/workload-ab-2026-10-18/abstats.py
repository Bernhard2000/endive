"""Reads and checks a workload A/B cell before anything derives statistics from it.

summarize.py and plot.py both go through here, so a cell with a missing arm, unpaired rounds or
an impossible sample fails loudly instead of producing a confident-looking partial estimate, and
guest-output comparison removes only what is known to vary between runs.

Samples: results/<cell>/results.<metric>.json holds runs_ns.up and runs_ns.fork, one sample per
round in round order (campaign.sh appends one per round), and round_orders, one entry per round.
Rounds are paired by that index. Outputs: outputs/<arm>.r<round>.out, guest stdout followed by
the harness's JSON record.
"""
import json, math, random, re, statistics
from pathlib import Path

ARMS = ("up", "fork")


class CellError(ValueError):
    """A cell whose data cannot support the statistics or the comparison asked of it."""


def load_runs(path):
    """(up, fork) sample lists of one results.<metric>.json, paired by round, after checks."""
    d = json.loads(Path(path).read_text())
    runs = d.get("runs_ns") or {}
    missing = [a for a in ARMS if a not in runs]
    if missing:
        raise CellError(f"{path}: no samples for arm(s) {', '.join(missing)}")
    up, fork = runs["up"], runs["fork"]
    if not up or len(up) != len(fork):
        raise CellError(f"{path}: {len(up)} upstream and {len(fork)} fork samples; "
                        "every round needs one sample of each arm")
    orders = d.get("round_orders")
    if orders is not None and len(orders) != len(up):
        raise CellError(f"{path}: {len(orders)} round orders for {len(up)} rounds")
    for arm, vals in (("up", up), ("fork", fork)):
        for i, v in enumerate(vals, 1):
            if (isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v)
                    or v <= 0):
                raise CellError(f"{path}: {arm} round {i} has an invalid sample {v!r}")
    return up, fork


def boot(vals, n=10000, seed=42):
    """Bootstrap 95 % interval of the median."""
    rng = random.Random(seed)
    out = sorted(statistics.median(rng.choices(vals, k=len(vals))) for _ in range(n))
    return out[int(0.025 * n)], out[int(0.975 * n)]


def paired_delta(up, fork):
    """fork/up per round: (median change %, CI low %, CI high %)."""
    ratios = [f / u for u, f in zip(up, fork)]
    lo, hi = boot(ratios)
    return (statistics.median(ratios) - 1) * 100, (lo - 1) * 100, (hi - 1) * 100


# Text that varies between runs of the same guest, by the label it follows. Everything else in
# guest stdout, guest JSON included, must match exactly.
VOLATILE = [
    # AWFY harness: "<B>: iterations=N runtime: Nus", "average: Nus total: Nus", "Total Runtime: Nus"
    (re.compile(r"\b(runtime|average|total|Total Runtime): \d+us\b"), r"\1: <t>us"),
    # speedtest1: " 100 - 50000 INSERTs....   0.123s"
    (re.compile(r"(\.{2,}\s+)\d+\.\d+s$"), r"\1<t>s"),
    # sqlite-app phase timings: "setup_ns=123"
    (re.compile(r"(_ns=)\d+"), r"\1<t>"),
]


def is_harness_record(line):
    """The EndiveRun telemetry line campaign.sh extracts, not guest output that is JSON."""
    if not line.startswith("{"):
        return False
    try:
        d = json.loads(line)
    except ValueError:
        return False
    return isinstance(d, dict) and d.get("label") in ARMS and "exec_ms_median" in d


def normalized(text):
    lines = []
    for line in text.splitlines():
        if is_harness_record(line):
            continue
        for rx, rep in VOLATILE:
            line = rx.sub(rep, line)
        lines.append(line)
    return "\n".join(lines)


OUT_NAME = re.compile(r"^(up|fork)\.r(\d+)\.out$")


def compare_outputs(outputs_dir, rounds, partial=False):
    """Compares every saved guest output of a cell. Returns a description such as
    "yes (24 of 24)"; raises CellError for a mismatch, a missing arm, or (unless partial)
    rounds without outputs. With partial, the rounds present for both arms are compared and the
    description says how many of the planned outputs that is."""
    found = {a: {} for a in ARMS}
    for p in sorted(Path(outputs_dir).glob("*.out")):
        m = OUT_NAME.match(p.name)
        if not m:
            raise CellError(f"{p}: not an <arm>.r<round>.out file")
        found[m.group(1)][int(m.group(2))] = p
    for a in ARMS:
        if not found[a]:
            raise CellError(f"{outputs_dir}: no outputs of arm {a}")
    planned = set(range(1, rounds + 1))
    common = set(found["up"]) & set(found["fork"])
    if not partial and (set(found["up"]) != planned or set(found["fork"]) != planned):
        raise CellError(f"{outputs_dir}: outputs for rounds up={sorted(found['up'])} "
                        f"fork={sorted(found['fork'])}, planned 1..{rounds}")
    if not common:
        raise CellError(f"{outputs_dir}: no round has outputs of both arms")
    variants = {}
    for r in sorted(common):
        for a in ARMS:
            variants.setdefault(normalized(found[a][r].read_text(errors="replace")), []).append(
                f"{a}.r{r}")
    if len(variants) != 1:
        groups = "; ".join(", ".join(v) for v in variants.values())
        raise CellError(f"{outputs_dir}: guest output differs between samples ({groups})")
    return f"yes ({2 * len(common)} of {2 * rounds})"
