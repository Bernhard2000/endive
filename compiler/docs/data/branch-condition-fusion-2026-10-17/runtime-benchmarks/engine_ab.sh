#!/usr/bin/env bash
# Engine A/B on fixed modules: upstream Endive main (A) vs. pr/fuse-branch-conditions (B).
# Fresh JVM per sample, permuted within-round order, L1 protocol verdicts.
#
# usage: engine_ab.sh NAME WASM ROUNDS WARMUP REPS [guest args...]
set -euo pipefail
export LC_NUMERIC=C
B="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
L1="/home/bernhard/Dokumente/MasterThesis/gitlab/experimental/endive-benchmark/wasi_exec/l1_protocol.sh"
JAVA_BIN="${JAVA_BIN:-/usr/bin/java}"
CORES="${BENCH_CORES:-4-7}"

name="$1" wasm="$2" rounds="$3" warmup="$4" reps="$5"; shift 5
out="$B/results/$name"
mkdir -p "$out/samples"
arms=(upstream pr)
cps=("$B/classes-A:$(<"$B/cp-A.txt")" "$B/classes-B:$(<"$B/cp-B.txt")")

source "$L1"
export BENCH_RUNS="$rounds" BENCH_CORES="$CORES"
bench_order_init
export BENCH_MODULES="$wasm"
export BENCH_ENGINES="$JAVA_BIN"
export BENCH_CONFIG="engine-ab upstream=1580f534 pr=3ba75b28 workload=$name args=${*:-none} warmup=$warmup reps=$reps cores=$CORES"
orders="$out/round_orders.txt"; : > "$orders"
for a in "${arms[@]}"; do
    for m in steady first compile; do : > "$out/samples/$a.$m.ns"; done
    : > "$out/$a.jsonl"
done

for round in $(seq 1 "$rounds"); do
    order="$(_bench_round_order "$round" 2)"
    printf '%s\n' "$(for i in $order; do printf '%s ' "${arms[$i]}"; done)" >> "$orders"
    for i in $order; do
        a="${arms[$i]}"
        line="$(taskset -c "$CORES" "$JAVA_BIN" -cp "${cps[$i]}" EndiveRun "$wasm" "$a" \
            "$warmup" "$reps" "$@" 2>>"$out/$a.stderr" | awk '/^\{/{l=$0} END{print l}')"
        [ -n "$line" ] || { echo "no JSON from $a round $round" >&2; exit 1; }
        printf '%s\n' "$line" >> "$out/$a.jsonl"
        python3 - "$line" "$out/samples/$a" <<'PY'
import json, sys
d = json.loads(sys.argv[1]); p = sys.argv[2]
for metric, key in (("steady", "exec_ms_median"), ("first", "first_exec_ms"), ("compile", "compile_ms")):
    open(f"{p}.{metric}.ns", "a").write(f"{int(float(d[key]) * 1e6)}\n")
PY
        printf '[%s] round %d %s exec_median=%s first=%s compile=%s\n' "$name" "$round" "$a" \
            "$(python3 -c 'import json,sys;d=json.loads(sys.argv[1]);print(d["exec_ms_median"],d["first_exec_ms"],d["compile_ms"])' "$line")"
    done
done

for m in steady first compile; do
    phase=steady; [ "$m" = first ] && phase=first-call; [ "$m" = compile ] && phase=instantiate
    echo "=== $name: $m ==="
    BENCH_ROUND_ORDERS="$orders" BENCH_VERDICT_JSON="$out/verdict.$m.json" \
        bench_emit_samples "$out/results.$m.json" "$phase" \
        "upstream=$out/samples/upstream.$m.ns" "pr=$out/samples/pr.$m.ns"
    bench_paired_verdict "$out/results.$m.json" upstream pr || true
done
