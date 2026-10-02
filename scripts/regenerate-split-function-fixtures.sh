#!/usr/bin/env bash
set -euo pipefail

binaryen_dir=${1:?Usage: regenerate-split-function-fixtures.sh /path/to/custom/binaryen}
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
fixtures_dir="$repo_dir/compiler-tests/src/test/resources/run/endive/testing"

# The custom executables must load their matching library, rather than a system libbinaryen.
for lib_dir in "$binaryen_dir/bin/lib" "$binaryen_dir/lib"; do
    if [[ -f "$lib_dir/libbinaryen.so" ]]; then
        export LD_LIBRARY_PATH="$lib_dir${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
        break
    fi
done
export BINARYEN_CORES=${BINARYEN_CORES:-1}

"$binaryen_dir/bin/wasm-as" "$fixtures_dir/split-helper-returns.wat" \
    --all-features -g -o "$fixtures_dir/split-helper-returns.wasm"

for arm in tuples mailbox; do
    mailbox_enabled=0
    if [[ "$arm" == mailbox ]]; then
        mailbox_enabled=1
    fi
    "$binaryen_dir/bin/wasm-opt" "$fixtures_dir/binaryen-split-abi.wat" \
        --function-splitting \
        --pass-arg=function-splitting-size-limit@180 \
        --pass-arg=function-splitting-size-metric@wasm \
        --pass-arg=function-splitting-jvm-limit@0 \
        --pass-arg=function-splitting-jit-limit@0 \
        --pass-arg=function-splitting-routed-globals@"$mailbox_enabled" \
        --pass-arg=function-splitting-routed-globals-min-liveouts@1 \
        --pass-arg=function-splitting-remove-budget@1 \
        --pass-arg=function-splitting-post-cleanup@0 \
        --all-features -g -o "$fixtures_dir/binaryen-split-$arm.wasm"
done
