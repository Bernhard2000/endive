#!/usr/bin/env bash
# Second-pass diagnostics; does not edit production sources or install artifacts.
# Runs against the classes of the checkout it lives in (build wasm, runtime and compiler first).
set -euo pipefail
B=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$B/../../../../.." && pwd)
M2=${ENDIVE_AUDIT_M2:-${HOME}/.m2/repository-endive-fork}
OUT=${ENDIVE_AUDIT_OUT:-$(mktemp -d /tmp/endive-audit-recheck.XXXXXX)}
mkdir -p "$OUT/classes"

CP="$ROOT/wasm/target/classes:$ROOT/runtime/target/classes:$ROOT/compiler/target/classes"
for component in asm asm-commons asm-util asm-tree asm-analysis; do
  jar="$M2/org/ow2/asm/$component/9.10.1/$component-9.10.1.jar"
  [[ -f "$jar" ]] || { echo "Missing $jar (build compiler dependencies first)" >&2; exit 1; }
  CP="$CP:$jar"
done

javac -nowarn --release 11 -cp "$CP" -d "$OUT/classes" "$B/TableOwnerProbe.java" \
  "$B/ManyParamsMixedProbe.java" "$B/ParseOnly.java"
# FuseDiff uses the fork's withUnsignedCompareFusion/withBranchlessBooleans builder options
if javac -nowarn --release 11 -cp "$CP" -d "$OUT/classes" "$B/FuseDiff.java" 2>/dev/null; then
  FUSE=1
else
  FUSE=0
fi
CP="$OUT/classes:$CP"
{
  echo 'Table initialiser owner (expected: result=42 ownerRecorded=true):'
  java -Xmx256m -cp "$CP" TableOwnerProbe \
    "$B/table_init_owner-export.wat.wasm" "$B/table_init_owner-import.wat.wasm"
  echo
  echo 'Boxed-ABI call_indirect across instances (expected 42 / 0 / 1):'
  java -Xmx256m -cp "$CP" ManyParamsMixedProbe \
    "$B/many_params_cross_module-export.wat.wasm" "$B/many_params_cross_module-import.wat.wasm"
  echo
  echo 'Validator, (ref null none) where eqref is expected (expected: valid):'
  for f in "$B"/none_*.wasm; do java -cp "$CP" ParseOnly "$f"; done
  echo
  if [[ $FUSE == 1 ]]; then
    echo 'Fusion differential, numeric (expected: mismatches=0):'
    java -Xmx512m -cp "$CP" FuseDiff "$B/fuse.wasm" | tail -1
    echo 'Fusion differential, references (only i_loop, the F01 shape, is known to mismatch):'
    java -Xmx256m -cp "$CP" FuseDiff "$B/gc.wasm" | awk '/^MISMATCH/ {seen[$2]++; next} {print}
      END {for (k in seen) print "mismatching export: " k " (" seen[k] " of the at most 40 printed mismatch lines)"}'
  else
    echo 'FuseDiff skipped: this checkout has no fork compiler options.'
  fi
} | tee "$OUT/results.txt"
printf '\nDiagnostic output: %s/results.txt\n' "$OUT"
