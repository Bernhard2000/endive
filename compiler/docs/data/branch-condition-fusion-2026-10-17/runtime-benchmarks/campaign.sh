#!/usr/bin/env bash
# Engine A/B campaign: upstream main (1580f534) vs pr/fuse-branch-conditions (3ba75b28).
set -euo pipefail
B="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
S="$B/splits"
export BENCH_ORDER_SEED="${BENCH_ORDER_SEED:-20261017}"
mkdir -p "$B/results"; cd "$B"
date --iso-8601=seconds > "$B/results/started_at.txt"

# name wasm rounds warmup reps args...
"$B/engine_ab.sh" lua-base       "$S/lua/base.wasm"           15 1 3 2 a
"$B/engine_ab.sh" lua-split      "$S/lua/split-8kB.wasm"      15 2 5 2 a
"$B/engine_ab.sh" sqlite-split   "$S/sqlite/split-8kB.wasm"   15 1 3
"$B/engine_ab.sh" sqlite-base    "$S/sqlite/base.wasm"        10 0 1
"$B/engine_ab.sh" quickjs-split  "$S/quickjs/split-8kB.wasm"  10 1 2 1

# Endive's own JMH suite (compiler-affected benchmarks + interpreter control), ABBAAB forks
JMH_RE='BenchmarkFactorialExecution|BenchmarkSievePrimes|BenchmarkWat2Wasm|BenchmarkDispatchChunkSize'
mkdir -p "$B/results/jmh"
i=0
for x in A B B A A B; do
    i=$((i + 1))
    taskset -c 4-7 /usr/bin/java -jar "$B/wt-$x/jmh/target/benchmarks.jar" "$JMH_RE" \
        -f 1 -wi 3 -w 2s -i 4 -r 2s -rf json -rff "$B/results/jmh/run$i-$x.json" \
        > "$B/results/jmh/run$i-$x.log" 2>&1
    echo "jmh run $i ($x) done"
done

"$B/engine_ab.sh" quickjs-base   "$S/quickjs/base.wasm"       5 0 1 1
date --iso-8601=seconds > "$B/results/finished_at.txt"
