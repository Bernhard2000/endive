#!/usr/bin/env bash
# smoke.sh ARM NAME WASM [args...] -> one sample (0 warmup, 1 rep), wall time, stdout
export LC_NUMERIC=C
B="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
arm="$1" name="$2" wasm="$3"; shift 3
mkdir -p "$B/smoke"
t0=$(date +%s.%N)
java -cp "$B/classes-$arm:$(<"$B/cp-$arm.txt")" EndiveRun "$wasm" "$arm" 0 1 "$@" > "$B/smoke/$name.$arm.out" 2> "$B/smoke/$name.$arm.err"
rc=$?
t1=$(date +%s.%N)
printf '%-22s %-5s rc=%s wall=%.1fs %s\n' "$name" "$arm" "$rc" "$(echo "$t1 - $t0" | bc)" "$(grep '^{' "$B/smoke/$name.$arm.out" | tail -1 | python3 -c 'import json,sys; d=json.loads(sys.stdin.read() or "{}"); print("exec",d.get("exec_ms_median"),"compile",d.get("compile_ms"))' 2>&1)"
