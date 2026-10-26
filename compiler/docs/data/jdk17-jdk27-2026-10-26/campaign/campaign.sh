#!/usr/bin/env bash
# JDK 17 workaround A/B, 2026-10-26.
#   up   = bytecodealliance/endive main bce75ae6 (the fork's base)
#   fork = fuse-branch-conditions c5219baf
# Arms (arms.sh): up17, fork17 (workaround on, as shipped), fork17noWA (workaround and
# unsigned fusion off: the cost of the workaround alone), up27, fork27 (Temurin 27.0.1).
# Fresh JVM per sample, seeded permuted arm order per round, guest stdout kept.
#
# usage: [START_ROUND=N] campaign.sh [ROUNDS] [workload-name-regex]
set -euo pipefail
export LC_NUMERIC=C
B="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
X=/home/bernhard/Dokumente/MasterThesis/gitlab/experimental
WX="$X/endive-benchmark/wasi_exec"
P="$X/results/2026-10-17/fork-fusion-ab/splits"
S="$B/../bench-workloads-2026-10-18/splits"
CORES="${BENCH_CORES:-4-7}"
ROUNDS="${1:-10}"
FILTER="${2:-.}"
export BENCH_ORDER=permuted BENCH_ORDER_SEED="${BENCH_ORDER_SEED:-20261026}"
LUA_AWFY="ENDIVE_MOUNT=/awfy=$X/awfy/benchmarks/Lua"
JS_AWFY="ENDIVE_MOUNT=/awfy=$X/awfy/benchmarks/JavaScript"

# name | module | warmup | reps | env | guest args
TABLE=(
  "lua-base|$WX/lua/lua_wasi.wasm|1|3|-|2 a"
  "lua-split|$P/lua/split-8kB.wasm|2|5|-|2 a"
  "sqlite-split|$P/sqlite/split-8kB.wasm|1|3|-|"
  "awfy-lua-split-Richards|$S/awfy/lua_awfy-split-8-64-bt.wasm|2|5|$LUA_AWFY|/awfy/harness.lua Richards 5 1"
  "awfy-js-split-Richards|$S/awfy/qjs_awfy-split-8-64-bt.wasm|1|3|$JS_AWFY|/awfy/harness.js Richards 2 1"
  "binjgb-split|$S/binjgb/split-8kB.wasm|1|3|-|400"
)

source "$B/arms.sh"
source "$WX/l1_protocol.sh"
bench_order_init
mkdir -p "$B/results"
date --iso-8601=seconds >> "$B/results/started_at.txt"

for row in "${TABLE[@]}"; do
  IFS='|' read -r name wasm warmup reps envs args <<< "$row"
  [[ "$name" =~ $FILTER ]] || continue
  out="$B/results/$name"
  START="${START_ROUND:-1}"
  if [ "$START" -eq 1 ]; then rm -rf "$out"; fi
  mkdir -p "$out/outputs"
  # resume: drop samples from rounds >= START (a partial round is redone in full)
  for f in "$out"/*.jsonl; do [ -e "$f" ] || continue
    python3 -c 'import json,sys;p,s=sys.argv[1],int(sys.argv[2]);L=[l for l in open(p) if json.loads(l)["round"]<s];open(p,"w").writelines(L)' "$f" "$START"; done
  envv=(); [ "$envs" = - ] || read -r -a envv <<< "$envs"
  guest=(); [ -z "$args" ] || read -r -a guest <<< "$args"
  sha256sum "$wasm" > "$out/module.sha256"
  orders="$out/round_orders.txt"; [ "$START" -gt 1 ] || : > "$orders"
  [ "$START" -eq 1 ] || { head -n $((START - 1)) "$orders" > "$orders.tmp"; mv "$orders.tmp" "$orders"; }
  for round in $(seq "$START" "$ROUNDS"); do
    order="$(_bench_round_order "$round" "${#ARMS[@]}")"
    names=""
    for i in $order; do
      IFS='|' read -r arm java cpa extra <<< "${ARMS[$i]}"
      names+="$arm "
      xv=(); [ -z "$extra" ] || read -r -a xv <<< "$extra"
      o="$out/outputs/$arm.r$round.out"
      env "${envv[@]}" taskset -c "$CORES" "$java" "${xv[@]}" -cp "$B/classes-$cpa:$(<"$B/cp-$cpa.txt")" \
          EndiveRun "$wasm" "$arm" "$warmup" "$reps" "${guest[@]}" > "$o" 2>>"$out/$arm.stderr" ||
        { echo "[$name] $arm round $round: exit $?" >&2; exit 1; }
      line="$(awk '/^\{/{l=$0} END{print l}' "$o")"
      [ -n "$line" ] || { echo "no JSON from $arm round $round ($name)" >&2; exit 1; }
      printf '{"round":%d,%s\n' "$round" "${line#\{}" >> "$out/$arm.jsonl"
      printf '[%s] %s r%d %s %s\n' "$(date +%T)" "$name" "$round" "$arm" \
        "$(python3 -c 'import json,sys;d=json.loads(sys.argv[1]);print("steady",d["exec_ms_median"],"first",d["first_exec_ms"],"compile",d["compile_ms"])' "$line")"
    done
    echo "$names" >> "$orders"
  done
done
date --iso-8601=seconds >> "$B/results/finished_at.txt"
echo CAMPAIGN-DONE
