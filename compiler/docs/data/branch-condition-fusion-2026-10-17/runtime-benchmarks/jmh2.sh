#!/usr/bin/env bash
# Rerun the path-dependent Endive JMH benchmarks from each worktree root, ABBAAB forks.
set -euo pipefail
B="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
until [ -f "$B/results/finished_at.txt" ]; do sleep 10; done
mkdir -p "$B/results/jmh2"
i=0
for x in A B B A A B; do
    i=$((i + 1))
    (cd "$B/wt-$x" && taskset -c 4-7 /usr/bin/java -jar jmh/target/benchmarks.jar \
        'BenchmarkFactorialExecution|BenchmarkSievePrimes' \
        -f 1 -wi 3 -w 2s -i 4 -r 2s -rf json -rff "$B/results/jmh2/run$i-$x.json" \
        > "$B/results/jmh2/run$i-$x.log" 2>&1)
    echo "jmh2 run $i ($x) done"
done
date --iso-8601=seconds > "$B/results/jmh2/finished_at.txt"
