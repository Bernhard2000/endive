#!/usr/bin/env bash
# Diagnostic reproducers for the audit; does not edit production sources or install artifacts.
set -euo pipefail
B=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(cd -- "$B/../../../.." && pwd)
M2=${ENDIVE_AUDIT_M2:-${HOME}/.m2/repository-endive-fork}
OUT=${ENDIVE_AUDIT_OUT:-$(mktemp -d /tmp/endive-audit-probes.XXXXXX)}
mkdir -p "$OUT/classes" "$OUT/fake-m2"

CP="$ROOT/wasm/target/classes:$ROOT/runtime/target/classes:$ROOT/compiler/target/classes"
for component in asm asm-commons asm-util asm-tree asm-analysis; do
  jar="$M2/org/ow2/asm/$component/9.10.1/$component-9.10.1.jar"
  [[ -f "$jar" ]] || { echo "Missing $jar (build compiler dependencies first)" >&2; exit 1; }
  CP="$CP:$jar"
done

javac --release 11 -cp "$CP" -d "$OUT/classes" \
  "$ROOT/dircache/src/main/java/run/endive/experimental/dircache/DirectoryCache.java" \
  "$B/Probe.java" "$B/CacheProbe.java" "$B/CancellationProbe.java" "$B/CrossInstanceProbe.java"
CP="$OUT/classes:$CP"
{
  echo 'Loop parameter / ref.eq (expected by Wasm semantics: 1):'
  java -Xmx256m -cp "$CP" Probe "$B/loop-ref-eq.wat.wasm"
  echo
  echo 'DirectoryCache option matrix:'
  java -Xmx256m -cp "$CP" CacheProbe "$B/loop-ref-eq.wat.wasm" "$OUT/cache"
  echo
  echo 'Interruption (all workers should stop):'
  # Each worker is a daemon. Even when cancellation fails, process exit removes every worker.
  timeout 20s java -Xmx128m -cp "$CP" CancellationProbe "$B/tail-cancellation.wat.wasm"
  echo
  echo 'Cross-instance indirect calls (expected: 42 for ordinary and tail):'
  java -Xmx256m -cp "$CP" CrossInstanceProbe "$B/cross-provider.wat.wasm" "$B/cross-consumer.wat.wasm"
  echo
  echo 'Benchmark reducer negative controls:'
  python3 "$B/summary-probe.py" "$ROOT"
  echo
  echo 'Wrapper split -D argument guard (fake Maven; no installation):'
  printf '#!/usr/bin/env bash\nprintf "%%s\\n" "$@"\n' > "$OUT/fake-mvn"
  chmod +x "$OUT/fake-mvn"
  ENDIVE_FORK_M2="$OUT/fake-m2" MVN="$OUT/fake-mvn" \
    "$ROOT/scripts/mvn-fork.sh" -D maven.repo.local=/tmp/another-repository
} | tee "$OUT/results.txt"
printf '\nDiagnostic output: %s/results.txt\n' "$OUT"
