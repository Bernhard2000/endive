#!/usr/bin/env bash
# bisect_arm.sh COMMIT -> builds the commit in its own worktree, prints rustvm-base exec ms (3 samples) vs up
set -euo pipefail
B="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; c="$1"
WX=/home/bernhard/Dokumente/MasterThesis/gitlab/experimental/endive-benchmark/wasi_exec
M=$HOME/.m2/repository-endive-fork/org/ow2/asm
T="$B/bisect/wt-$c"
if [ ! -f "$B/bisect/cp-$c.txt" ]; then
  mkdir -p "$B/bisect"
  git -C /home/bernhard/Dokumente/MasterThesis/Endive/fork/endive worktree add -q --detach "$T" "$c"
  (cd "$T" && ./mvnw -B -q -Dmaven.repo.local=$HOME/.m2/repository-endive-fork -Dquickly -Dspotless.skip=true package -pl compiler,wasi -am) > "$B/bisect/build-$c.log" 2>&1
  cp=""; for m in wasm runtime log wasi compiler annotations/annotations; do cp="$cp$T/$m/target/$(basename $m)-999-SNAPSHOT.jar:"; done
  for a in asm asm-commons asm-tree asm-util asm-analysis; do cp="$cp$M/$a/9.10.1/$a-9.10.1.jar:"; done
  mkdir -p "$B/bisect/classes-$c"; javac --release 17 -nowarn -cp "${cp%:}" -d "$B/bisect/classes-$c" "$B/EndiveRun.java" 2>/dev/null
  echo "${cp%:}" > "$B/bisect/cp-$c.txt"
fi
run() { taskset -c 4-7 java -cp "$1" EndiveRun "$WX/rustvm/rustvm_wasi.wasm" x 0 2 | grep '^{' | python3 -c 'import json,sys;print(round(json.load(sys.stdin)["exec_ms_median"]))'; }
u=(); k=()
for r in 1 2 3; do u+=("$(run "$B/classes-up:$(<"$B/cp-up.txt")")"); k+=("$(run "$B/bisect/classes-$c:$(<"$B/bisect/cp-$c.txt")")"); done
echo "$c up=${u[*]} cand=${k[*]}"
