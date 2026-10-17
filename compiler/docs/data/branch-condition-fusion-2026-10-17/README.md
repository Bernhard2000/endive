# Branch-condition fusion extensions: evidence (2026-10-17)

These are the raw measurements behind
[branch-condition-fusion-extensions.md §8](../../branch-condition-fusion-extensions.md#8-measurements).
They were taken locally on OpenJDK 21.0.12.1. All counts are static bytecode counts; nothing
here was timed.

## Revisions

| label | commit | what |
| --- | --- | --- |
| `out-up` | `1580f534` | upstream `bytecodealliance/endive` main |
| `out-pr1` | `e4105edf` | `pr/fuse-branch-conditions` commit 1: the original fusion (+ EnumOrdinal fix) |
| `out-pr` | `3ba75b28` | `pr/fuse-branch-conditions` commit 2: the extensions |
| `out-head` | `82f39301` | fork branch head before the extensions (includes non-fusion fork work such as the switch encoding) |
| `out-new` | `5a02a7dd` | fork branch with the extensions |

## Files

| file | content |
| --- | --- |
| `FusionDumpTest.java` | JUnit harness. Copy it into `compiler/src/test/java/run/endive/compiler/internal/` and run it with `-Dfusion.out=<dir>`. It compiles the listed corpus modules and writes every generated class to `<dir>/<module>/`. `ROOT` is an absolute path, so adjust it first. |
| `FusionShowTest.java` | Same idea. It prints the ASM text of selected fixture functions to the file `-Dfusion.out=<file>`. |
| `method_sizes.py` | `python3 method_sizes.py <base dir> <variant>...` sums the `func_*` `Code` lengths and counts methods over 8000 bytes. It reuses `../split-function-optimization-2026-10-02/parse_class_methods.py`. |
| `helper_calls.sh` | `helper_calls.sh <dir>/<module>` uses `javap` to count comparison and null-test helper `invokestatic`s (plus `Long.compareUnsigned`). |
| `method-sizes-upstream-vs-pr.txt` | `method_sizes.py` output for `out-up out-pr1 out-pr` |
| `method-sizes-fork-head-vs-extension.txt` | `method_sizes.py` output for `out-head out-new` |
| `helper-calls-upstream-vs-pr.txt` | `helper_calls.sh` output per module and revision |
| `bytecode-before.txt`, `bytecode-after.txt` | `FusionShowTest` output on `82f39301` and `5a02a7dd` |

## Reproduce

```sh
# in a worktree of each revision:
cp FusionDumpTest.java compiler/src/test/java/run/endive/compiler/internal/
./mvnw -o -q -pl compiler test -Dtest=FusionDumpTest -Dsurefire.failIfNoSpecifiedTests=false \
    -Ddev -Dfusion.out=$OUT/out-<label>
# then:
python3 method_sizes.py $OUT out-up out-pr1 out-pr
for v in out-up out-pr1 out-pr; do ./helper_calls.sh $OUT/$v/main.go.wasm; done
```

The compiler module's tests read `run.endive:*` dependencies from `~/.m2`. Install the
revision being measured first, or at least make sure the installed runtime matches it.
