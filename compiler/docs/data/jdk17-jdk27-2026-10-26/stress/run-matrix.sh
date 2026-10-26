#!/usr/bin/env bash
# config: name jdk variant extra-jvm-args
cd "$(dirname "$0")"
ITER=${ITER:-25}; RUNS=${RUNS:-3}
J17=../jdk-17.0.21+9 J17OLD=/home/bernhard/Dokumente/jdk-17.0.7+7 J27=../jdk-27.0.1.0.2+1
configs=(
 "17.0.21-default-auto $J17 default"
 "17.0.21-default-woOFF $J17 default -Dendive.memCopyWorkaround=false"
 "17.0.21-fusion-auto $J17 fusion"
 "17.0.21-fusion-woOFF $J17 fusion -Dendive.memCopyWorkaround=false"
 "17.0.7-default-woOFF $J17OLD default -Dendive.memCopyWorkaround=false"
 "17.0.7-fusion-woOFF $J17OLD fusion -Dendive.memCopyWorkaround=false"
 "27.0.1-default-auto $J27 default"
 "27.0.1-default-woON $J27 default -Dendive.memCopyWorkaround=true"
 "27.0.1-fusion-auto $J27 fusion"
)
mkdir -p out
run() { set -- $1; name=$1 jdk=$2 var=$3; shift 3
  for r in $(seq 1 $RUNS); do
    $jdk/bin/java "$@" -cp "cls:wabt-$var-classes:$(cat wabt.cp)" Wat2WasmStress $ITER > out/$name.run$r.log 2>&1
    echo "$name run$r rc=$? $(tail -1 out/$name.run$r.log)"
  done; }
export -f run; export ITER RUNS
printf '%s\n' "${configs[@]}" | xargs -P 4 -I{} bash -c 'run "$@"' _ {}
echo MATRIX-DONE
