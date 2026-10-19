#!/usr/bin/env bash
# Engine A/B on fixed modules, extended workload set:
#   up   = bytecodealliance/endive main 1580f534
#   fork = Bernhard2000/endive fuse-branch-conditions 44f68a6f (code = 7e743d80)
# Fresh JVM per sample, seeded permuted arm order per round, L1 protocol verdicts.
# Guest stdout of every sample is kept (outputs/) for the correctness comparison.
#
# usage: campaign.sh [ROUNDS] [workload-name-regex]
set -euo pipefail
export LC_NUMERIC=C
B="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
X=/home/bernhard/Dokumente/MasterThesis/gitlab/experimental
WX="$X/endive-benchmark/wasi_exec"
P="$X/results/2026-10-17/fork-fusion-ab/splits"     # pinned thesis split inputs
S="$B/splits"                                       # splits made for this run
L1="$WX/l1_protocol.sh"
JAVA_BIN="${JAVA_BIN:-/usr/bin/java}"
CORES="${BENCH_CORES:-4-7}"
ROUNDS="${1:-12}"
FILTER="${2:-.}"
export BENCH_ORDER_SEED="${BENCH_ORDER_SEED:-20261018}"
LUA_AWFY="ENDIVE_MOUNT=/awfy=$X/awfy/benchmarks/Lua"
JS_AWFY="ENDIVE_MOUNT=/awfy=$X/awfy/benchmarks/JavaScript"
PY_ENV="ENDIVE_MOUNT=/lib=$X/endive-benchmark/lib ENDIVE_ARGV0=python.wasm"
PY_SRC="$(<"$X/endive-benchmark/src/main/resources/wasi_python_workload.py")"

# name | module | warmup | reps | env (space-separated K=V or -) | guest args
TABLE=(
  "lua-base|$WX/lua/lua_wasi.wasm|1|3|-|2 a"
  "lua-split|$P/lua/split-8kB.wasm|2|5|-|2 a"
  "sqlite-split|$P/sqlite/split-8kB.wasm|1|3|-|"
  "quickjs-split|$P/quickjs/split-8kB.wasm|1|2|-|1"
  "python-split|$P/python/split-8kB-64kB.wasm|1|3|$PY_ENV|@python"
  "binjgb-base|$S/binjgb/base.wasm|1|3|-|400"
  "binjgb-split|$S/binjgb/split-8kB.wasm|1|3|-|400"
  "i8080-base|$S/i8080/base.wasm|0|2|-|"
  "i8080-split|$S/i8080/split-8kB.wasm|1|3|-|"
  "z80-base|$S/z80/base.wasm|0|2|-|"
  "z80-split|$S/z80/split-8kB.wasm|1|3|-|"
  "genesis-base|$S/genesis/base.wasm|1|3|-|120"
  "genesis-split|$S/genesis/split-8kB.wasm|1|3|-|120"
  "rustvm-base|$WX/rustvm/rustvm_wasi.wasm|0|2|-|"
  "rustvm-split|$WX/rustvm/rustvm_pgo.wasm|1|3|-|"
  "fkernel-base|$WX/fkernel/fkernel_wasi.wasm|0|2|-|"
  "fkernel-split|$WX/fkernel/fkernel_pgo.wasm|2|5|-|"
  "rv32ima-base|$WX/rv32ima/rv32ima_wasi.wasm|1|3|-|"
  "sqlite-app-split|$S/sqlite_app-split-8-64-bt.wasm|1|3|-|--mode inmem"
  "speedtest1-split|$S/speedtest1-split-8-64-bt.wasm|1|3|-|--memdb --size 5 --testset mix1 --verify benchmark.db"
  "awfy-lua-split-Richards|$S/awfy/lua_awfy-split-8-64-bt.wasm|2|5|$LUA_AWFY|/awfy/harness.lua Richards 5 1"
  "awfy-lua-split-DeltaBlue|$S/awfy/lua_awfy-split-8-64-bt.wasm|2|5|$LUA_AWFY|/awfy/harness.lua DeltaBlue 100 1"
  "awfy-lua-split-Json|$S/awfy/lua_awfy-split-8-64-bt.wasm|2|5|$LUA_AWFY|/awfy/harness.lua Json 10 1"
  "awfy-lua-split-NBody|$S/awfy/lua_awfy-split-8-64-bt.wasm|2|5|$LUA_AWFY|/awfy/harness.lua NBody 500 1"
  "awfy-lua-split-NBody-long|$S/awfy/lua_awfy-split-8-64-bt.wasm|3|5|$LUA_AWFY|/awfy/harness.lua NBody 5000 1"
  "awfy-lua-split-CD|$S/awfy/lua_awfy-split-8-64-bt.wasm|2|5|$LUA_AWFY|/awfy/harness.lua CD 150 2"
  "awfy-js-split-Richards|$S/awfy/qjs_awfy-split-8-64-bt.wasm|1|3|$JS_AWFY|/awfy/harness.js Richards 2 1"
  "awfy-js-split-DeltaBlue|$S/awfy/qjs_awfy-split-8-64-bt.wasm|1|3|$JS_AWFY|/awfy/harness.js DeltaBlue 10 1"
  "awfy-js-split-Json|$S/awfy/qjs_awfy-split-8-64-bt.wasm|1|3|$JS_AWFY|/awfy/harness.js Json 3 1"
  "awfy-js-split-NBody|$S/awfy/qjs_awfy-split-8-64-bt.wasm|1|3|$JS_AWFY|/awfy/harness.js NBody 12 1"
  "awfy-js-split-Bounce|$S/awfy/qjs_awfy-split-8-64-bt.wasm|1|3|$JS_AWFY|/awfy/harness.js Bounce 6 1"
  "awfy-js-split-CD|$S/awfy/qjs_awfy-split-8-64-bt.wasm|1|3|$JS_AWFY|/awfy/harness.js CD 3 2"
  "awfy-lua-unsplit-Richards|$S/awfy/lua_awfy-base.wasm|1|2|$LUA_AWFY|/awfy/harness.lua Richards 5 1"
  "awfy-lua-unsplit-DeltaBlue|$S/awfy/lua_awfy-base.wasm|2|5|$LUA_AWFY|/awfy/harness.lua DeltaBlue 100 1"
  "awfy-lua-unsplit-Json|$S/awfy/lua_awfy-base.wasm|1|3|$LUA_AWFY|/awfy/harness.lua Json 10 1"
  "awfy-lua-unsplit-NBody|$S/awfy/lua_awfy-base.wasm|2|5|$LUA_AWFY|/awfy/harness.lua NBody 500 1"
  "awfy-lua-unsplit-CD|$S/awfy/lua_awfy-base.wasm|1|1|$LUA_AWFY|/awfy/harness.lua CD 150 2"
  "awfy-js-unsplit-Richards|$S/awfy/qjs_awfy-base.wasm|1|2|$JS_AWFY|/awfy/harness.js Richards 2 1"
  "awfy-js-unsplit-DeltaBlue|$S/awfy/qjs_awfy-base.wasm|1|3|$JS_AWFY|/awfy/harness.js DeltaBlue 10 1"
  "awfy-js-unsplit-Json|$S/awfy/qjs_awfy-base.wasm|1|3|$JS_AWFY|/awfy/harness.js Json 3 1"
  "awfy-js-unsplit-NBody|$S/awfy/qjs_awfy-base.wasm|1|3|$JS_AWFY|/awfy/harness.js NBody 12 1"
  "awfy-js-unsplit-Bounce|$S/awfy/qjs_awfy-base.wasm|1|3|$JS_AWFY|/awfy/harness.js Bounce 6 1"
  "awfy-js-unsplit-CD|$S/awfy/qjs_awfy-base.wasm|1|3|$JS_AWFY|/awfy/harness.js CD 3 2"
)

arms=(up fork)
cps=("$B/classes-up:$(<"$B/cp-up.txt")" "$B/classes-fork:$(<"$B/cp-fork.txt")")
source "$L1"
mkdir -p "$B/results"
date --iso-8601=seconds >> "$B/results/started_at.txt"

for row in "${TABLE[@]}"; do
  IFS='|' read -r name wasm warmup reps envs args <<< "$row"
  [[ "$name" =~ $FILTER ]] || continue
  out="$B/results/$name"
  rm -rf "$out"; mkdir -p "$out/samples" "$out/outputs"
  envv=(); [ "$envs" = - ] || read -r -a envv <<< "$envs"
  guest=()
  if [ "$args" = @python ]; then guest=(-c "$PY_SRC")
  elif [ -n "$args" ]; then read -r -a guest <<< "$args"; fi
  export BENCH_RUNS="$ROUNDS" BENCH_CORES="$CORES" BENCH_MODULES="$wasm" BENCH_ENGINES="$JAVA_BIN"
  export BENCH_CONFIG="engine-ab up=1580f534 fork=44f68a6f workload=$name args=${args:-none} warmup=$warmup reps=$reps cores=$CORES"
  bench_order_init
  orders="$out/round_orders.txt"; : > "$orders"
  sha256sum "$wasm" > "$out/module.sha256"
  for a in "${arms[@]}"; do
    for m in steady first compile; do : > "$out/samples/$a.$m.ns"; done
    : > "$out/$a.jsonl"
  done
  for round in $(seq 1 "$ROUNDS"); do
    order="$(_bench_round_order "$round" 2)"
    printf '%s\n' "$(for i in $order; do printf '%s ' "${arms[$i]}"; done)" >> "$orders"
    for i in $order; do
      a="${arms[$i]}"
      o="$out/outputs/$a.r$round.out"
      env "${envv[@]}" taskset -c "$CORES" "$JAVA_BIN" -cp "${cps[$i]}" EndiveRun "$wasm" "$a" \
          "$warmup" "$reps" "${guest[@]}" > "$o" 2>>"$out/$a.stderr" ||
        { echo "[$name] $a round $round: exit $?" >&2; exit 1; }
      line="$(awk '/^\{/{l=$0} END{print l}' "$o")"
      [ -n "$line" ] || { echo "no JSON from $a round $round ($name)" >&2; exit 1; }
      printf '%s\n' "$line" >> "$out/$a.jsonl"
      python3 - "$line" "$out/samples/$a" <<'PY'
import json, sys
d = json.loads(sys.argv[1]); p = sys.argv[2]
for metric, key in (("steady", "exec_ms_median"), ("first", "first_exec_ms"), ("compile", "compile_ms")):
    open(f"{p}.{metric}.ns", "a").write(f"{int(float(d[key]) * 1e6)}\n")
PY
      printf '[%s] %s round %d %s %s\n' "$(date +%T)" "$name" "$round" "$a" \
        "$(python3 -c 'import json,sys;d=json.loads(sys.argv[1]);print("steady",d["exec_ms_median"],"first",d["first_exec_ms"],"compile",d["compile_ms"])' "$line")"
    done
  done
  for m in steady first compile; do
    phase=steady; [ "$m" = first ] && phase=first-call; [ "$m" = compile ] && phase=instantiate
    BENCH_ROUND_ORDERS="$orders" BENCH_VERDICT_JSON="$out/verdict.$m.json" \
      bench_emit_samples "$out/results.$m.json" "$phase" \
      "up=$out/samples/up.$m.ns" "fork=$out/samples/fork.$m.ns" > /dev/null
  done
done
date --iso-8601=seconds >> "$B/results/finished_at.txt"
