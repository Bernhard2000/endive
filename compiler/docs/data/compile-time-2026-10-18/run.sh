#!/usr/bin/env bash
# Interleaved compile-time A/B: fresh JVM per sample, arms in a seeded random order per round.
# usage: run.sh OUT.csv ROUNDS ITERATIONS MODULE... ; arms from $ARMS_DIR/cp-<arm>, harness in $ARMS_DIR
set -euo pipefail
out="$1" rounds="$2" iterations="$3"; shift 3
arms=(${ARMS:-upstream fusion fork})
echo "round,module,arm,first_us,median_later_us" > "$out"
for r in $(seq 1 "$rounds"); do
  for m in "$@"; do
    for a in $(python3 -c "import random,sys; a=sys.argv[2:]; random.Random(f'ct:{sys.argv[1]}').shuffle(a); print(*a)" "$r:$m" "${arms[@]}"); do
      res="$(java -cp "$ARMS_DIR:$(cat "$ARMS_DIR/cp-$a")" CompileTime "$m" "$iterations")"
      f="${res#first_us=}"; f="${f%% *}"; l="${res##*median_later_us=}"
      echo "$r,$(basename "$m"),$a,$f,$l" >> "$out"
    done
  done
done
