# Split-function optimization results

2026-10-02. Baseline: `854e77ca`. Validation JVM: OpenJDK 21.0.12.1.

Sparse Wasm branch tables now use a smaller JVM dispatch encoding when it saves bytecode.
Valid functions with unusually wide multivalue results also compile successfully instead of
failing while constructing the result-packing method signature.

## Implemented changes

`SwitchEmitter` compares `tableswitch` and `lookupswitch` sizes. It omits lookup entries that
equal the default target. When a different target dominates the table, explicit negative and
upper-bound checks preserve the original Wasm out-of-range default, allowing that dominant
target to become the lookup default. Alternate-default rewriting is restricted to tables whose
encoding alone reaches 8,000 bytes: the local small-table timings did not establish a benefit
from adding bounds checks. The size comparison includes conservative overhead for the guards
and alignment. Dense tables retain `tableswitch`; default-only tables use a pop and
jump. Backward-edge interruption checks remain in `Compiler`.

Wide returns previously passed every result through a generated `value_xxx` method. An `i32`
route plus 128 `i64` values requires 257 JVM parameter slots, exceeding the static method
descriptor limit. The baseline throws `IllegalArgumentException: bad parameter count 257`.
Result tuples above 255 slots are now packed directly from reserved temporary locals. This
supports both `long[]` and mixed reference/numeric `Object[]` results. Smaller results retain
their existing packing helper.

## Real CPython bytecode and execution checks

Input: `experimental/endive-benchmark/src/main/resources/python_base.wasm`, SHA-256
`43af342b99207576c27a2151354b98e986230fd047a4b3883020102df2a3e132`.

| Generated JVM method | Baseline Code.code_length | Final Code.code_length |
|---|---:|---:|
| `_PyUnicode_ToNumeric_3281` | 65,469 bytes | 47,793 bytes |

This is a 27% bytecode reduction. The method remains above the splitting JIT target. Both module
compilations retain two interpreter-fallback functions; this result is a reduction in emitted
bytecode, not evidence that CPython as a whole became faster.

The baseline and final generated numeric methods returned identical double bit patterns for
all 1,114,112 Unicode code points and six additional selectors, including negative values and
the signed integer limits. This is a direct comparison of that pure generated function, with
unused Memory/Instance parameters set to null; it does not exercise the complete CPython WASI
program. [Raw sizes and module pin](data/split-function-optimization-2026-10-02/python-method-size.json).

## Local dispatch timing

The synthetic 4,096-entry dispatch has three rare cases and a distinct out-of-range target.
The final encoding uses three lookup keys and explicit bounds checks.

| Compiler | Average ns/op | JMH confidence interval | Allocated B/op |
|---|---:|---:|---:|
| Baseline | 136.22 | 104.16–168.28 | 24.00 |
| Final | 30.63 | 22.72–38.55 | 24.00 |

These are sequential local trials, final then baseline: two JVM forks, three one-second warmups,
five one-second measurements, and a 512 MiB heap. The confidence intervals do not account for
host drift or run order. They support further workload testing of this large-table case, without
establishing a general split-function speedup. The 128-entry dominant-target case retains its
original table encoding. [Baseline raw results](data/split-function-optimization-2026-10-02/dispatch-baseline-verified-jmh.json),
[final raw results](data/split-function-optimization-2026-10-02/dispatch-final-verified-jmh.json).

## Verification

- Ten focused regression tests cover sparse/default/dense dispatch, selector boundaries,
  interruption, tuple values, shared returns, interpreter fallback, host normalization, GC
  results, exceptions, tail calls, and wide numeric/reference tuples.
- The actual custom Binaryen pass generated both tuple and routed-global fixtures. They contain
  six and seventeen defined functions respectively, from a three-function input. Their `_start`
  assertions test recursive re-entry and both routes of each outlined parent. Endive's compiler
  and interpreter agree, and these modules compile without fallback.
- Final compiler tests: 41 compiler unit tests and 28 tests in the compiler-tests module pass on
  both the classpath and module path. The large-class suite also passed earlier, with its existing disabled case;
  it was excluded from the final repeat after removing the forwarding prototype.
- 1,051 freshly compiled, cached specification cases pass on the final compiler. Coverage includes
  numeric and GC `br_table`, direct/indirect calls, returns, exception handling, tail calls,
  reference operations, and GC return calls.
- Formatting, Checkstyle, Error Prone compilation, dependency analysis, JMH module compilation,
  shell syntax, and `git diff --check` pass. Fixture regeneration reproduces all three Wasm files
  byte for byte.

The full upstream dependency reactor stopped while rebuilding bundled `wasm-tools`, whose
function 4725 exceeds the JVM method-size limit (2026-10-17: caused by a stale
`wasm-tools.wasm` download, not by the compiler; see
[branch-condition-fusion-extensions.md §11](branch-condition-fusion-extensions.md#11-incidental-findings)).
Compiler/JMH module checks use the already
available dependencies. Specification checks use the existing generated inputs, compiled
afresh against this compiler; they do not claim fresh generation of the entire specification
suite.

## Deferred tuple-forwarding experiment

A prototype returned a compiled helper's result array directly from its caller. It passed
correctness checks and removed unpack/repack bytecode. HotSpot already eliminated the
intermediate arrays in the synthetic chains: normalized allocation remained 32, 80, and 272
bytes per invocation for 2, 8, and 32 results.

Timing varied considerably between trials. Some matrix cells became slower; a subsequent
32-result A/B/A check had overlapping JMH confidence intervals:

| Trial | Average ns/op | JMH confidence interval |
|---|---:|---:|
| Baseline A | 15.72 | 13.92–17.52 |
| Forwarding B | 15.90 | 13.61–18.18 |
| Baseline A | 17.15 | 15.19–19.12 |

The final diff excludes forwarding. Revisit it with actual split helper bodies, JVM inlining
logs, and an interleaved workload campaign that demonstrates a benefit or a useful method-size
threshold crossing. [Prototype matrix baseline](data/split-function-optimization-2026-10-02/prototype-baseline-jmh.json),
[prototype matrix forwarding](data/split-function-optimization-2026-10-02/prototype-forwarding-jmh.json),
[A/B/A raw results](data/split-function-optimization-2026-10-02/tuple-forwarding-aba.json).

## Reproduction

Run module tests with the normal Maven checks enabled:

```bash
./mvnw -o -pl compiler,compiler-tests,jmh -Dwasm-test-gen.skip=true test
```

The skip flag uses the available dependency artifacts and runs the hand-written tests. To
generate and run specification tests through Maven, omit that flag. The cached specification
validation used JUnit Platform 1.14.4 and the saved `RunSpecs.java` launcher in the data directory.

Regenerate the fixtures using the custom Binaryen build:

```bash
./scripts/regenerate-split-function-fixtures.sh /path/to/custom/binaryen
```

The script selects the build's matching shared library. Original executable/library and fixture
hashes are in [fixture-provenance.json](data/split-function-optimization-2026-10-02/fixture-provenance.json).
The final regeneration check records its tool pins separately in
[fixture-regeneration-verification.json](data/split-function-optimization-2026-10-02/fixture-regeneration-verification.json).

Run `BenchmarkSplitFunctionBoundaries` through the existing JMH workflow with `-prof gc`.
The data directory also contains `CodeProbe.java`, `parse_class_methods.py`, and
`CompareUnicode.java` for generating, reading, and comparing the JVM classes using the Maven
test classpath. Baseline timing trials rebuilt the compiler sources from `git archive HEAD`
with `javac --release 11`; final trials use this checkout's compiler classes.

Rebuild compiled Wasm artifacts and the Endive JVM-size oracle with this compiler before
retuning Binaryen splitting budgets. Existing compiled artifacts and the older size model
retain the previous branch-table costs. These checks are local diagnostics, with no
fixed-runner or thesis runtime-performance acceptance claimed.
