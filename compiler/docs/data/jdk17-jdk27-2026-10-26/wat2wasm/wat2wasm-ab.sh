#!/usr/bin/env bash
# wat2wasm (build-time compiled) A/B: per-iteration times of Wat2WasmStress, 12 iterations per JVM.
cd "$(dirname "$0")"; source arms.sh; mkdir -p results-wat2wasm  # copied here as results/
CPUP="../jdks/stress/cls:wt-up/wabt/target/classes:$(cat wabt-up.cp)"
CPFORK="../jdks/stress/cls:../jdks/stress/wabt-default-classes:$(cat ../jdks/stress/wabt.cp)"
for round in 1 2 3; do
  for i in $(ONLY="${ONLY:-0 1 2 3 4}" python3 -c "import random;r=random.Random(20261026+$round);import os;l=list(range(5));r.shuffle(l);o=set(map(int,os.environ[\"ONLY\"].split()));print(*[x for x in l if x in o])"); do
    IFS='|' read -r arm java cpa extra <<< "${ARMS[$i]}"
    [ $cpa = up ] && cp="$CPUP" || cp="$CPFORK"
    taskset -c 4-7 $java $extra -cp "$cp" Wat2WasmStress 12 > results-wat2wasm/$arm.r$round.log 2>&1
    echo "$arm r$round rc=$? $(tail -1 results-wat2wasm/$arm.r$round.log)"
  done
done
echo W2W-DONE
