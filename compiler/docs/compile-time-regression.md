# The runtime-compile regression (fork plan §3.6)

Status: investigated 2026-10-18 on a laptop (exploratory, not fixed-runner evidence). Raw
data: [data/compile-time-2026-10-18/](data/compile-time-2026-10-18/).

## Question

The fusion benchmarks ([branch-condition-fusion-benchmarks.md](branch-condition-fusion-benchmarks.md),
finding 4) measured runtime compilation 2.2–2.5 % (about 20 ms) slower on unsplit Lua and SQLite.
Suspects were `takeCondition`'s list operations, new constant-pool entries and ASM frame
computation.

## Measurement

`CompileTime.java` times `Compiler.builder(module).build().compile()`: the first compile of a
fresh JVM (what an instantiation with `MachineFactoryCompiler` pays) and, separately, the median
of compiles 2–15 in one JVM (the compiler itself JIT-compiled). `run.sh` interleaves the arms in
a seeded random order per round; `summarize.py` reports the paired per-round ratio with a
bootstrap CI. Arms: upstream main `1580f534`, the original fusion alone (`e4105edf`), the fork
(`962d4ca8` before the fix, then with it). OpenJDK 21.0.12.1, laptop, `ondemand` governor.

**Before the fix** (15 fresh-JVM rounds; 5 warm rounds):

| module | arm | first compile Δ | 95 % CI | warm compile Δ | 95 % CI |
| --- | --- | ---: | --- | ---: | --- |
| Lua | fusion | +2.1 % | [−3.1, +5.6] | +0.3 % | [−5.1, +14.8] |
| Lua | fork | +4.9 % | [+2.9, +8.8] | +6.2 % | [−6.8, +7.9] |
| SQLite | fusion | +4.3 % | [+2.2, +7.8] | −1.0 % | [−4.1, +6.1] |
| SQLite | fork | +7.5 % | [+3.5, +12.2] | +2.8 % | [−1.3, +4.6] |

So the regression is a **cold-JVM** cost: once the compiler is warm, no arm is distinguishable
from upstream.

**Where.** JFR (`jdk.ExecutionSample` at 1 ms, five first compiles of SQLite per arm,
`jfr_top.py`, inclusive shares): `WasmAnalyzer.analyze` went from 11.2 % of samples (upstream)
to 14.1 % (fork). The two post-passes account for the difference: `fuseBranchConditions` 1.6 %
and `NarrowingPeephole.apply` 1.4 %. Each walks the finished instruction list and copies it into
a new `ArrayList`; on a cold JVM these loops run interpreted or C1-compiled. `takeCondition`'s
removals are at the end of an `ArrayList` and do not show up; ASM frame computation is *lower*
on the fork (17.2 % → 12.1 % self time in `org.objectweb.asm.Frame`), since fused methods have
fewer instructions; constant-pool work (`SymbolTable`) is unchanged.

## Fix

The analyzer only appends to its output list (apart from prepending the try-catch block
markers at the end). `WasmAnalyzer.PeepholeList` runs the narrowing peephole and
branch-condition fusion as each instruction is appended (`NarrowingPeephole.append`,
`WasmAnalyzer.appendFused`), so neither needs a pass over, or a copy of, the finished list. The
batch entry points remain and are what `PeepholeListTest` compares against: for every function of
Go, .NET and all condition fixtures, with and without unsigned compare fusion, the incremental
output equals the two batch passes instruction for instruction.

**After the fix** (15 fresh-JVM rounds):

| module | arm | first compile Δ | 95 % CI |
| --- | --- | ---: | --- |
| Lua | fusion | +1.2 % | [−0.9, +2.2] |
| Lua | fork | +3.1 % | [−1.4, +7.7] |
| SQLite | fusion | +3.6 % | [+1.2, +5.1] |
| SQLite | fork | +3.6 % | [+1.2, +5.0] |

The fork's cold-compile overhead halved and is now that of the original fusion alone, although
the fork does more (narrowing, `ref.eq`, sound scope types). The residual is the per-instruction
dispatch into the peephole step (`PeepholeList.add`, 1.1 % self time) on a cold JVM; warm, it is
not measurable. A further cut would mean fusing inside every `result.add` site of the analyzer,
which is not worth the complexity for about 1 % of a one-time cost.
