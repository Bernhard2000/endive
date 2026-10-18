# A JMH benchmark for large generated methods (fork plan §3.5)

Status: added in `7ef91208`, measured 2026-10-18 on a laptop (OpenJDK 21.0.12.1, `ondemand`
governor; exploratory). Raw data: [data/large-method-2026-10-18/](data/large-method-2026-10-18/).

## Why

Upstream's Perf workflow runs Endive's JMH suite, whose compiled benchmarks are small functions.
C2 inlines the comparison helpers there and folds their 0/1 results away, so the suite cannot see
how the compiler lowers comparisons, and it reported "no change" for branch-condition fusion
while split workloads ran 4–10 % faster.

## The benchmark

`BenchmarkLargeMethod` generates one function with a loop over N comparison sites, cycling
through `i32.lt_s`, `i32.lt_u` with a variable bound, `i64.lt_s`, `i32.eqz` of a mask,
`f64.lt`, `i32.ge_u` against `-11` (a constant without a bias-free rewrite) in `if`s, and an
`i64.eqz` and an `i32.ne` used as values.

| `sites` | method size upstream | fork | what HotSpot does |
| --- | ---: | ---: | --- |
| 320 | 7,797 B | 7,312 B | C2 compiles it; the helpers compete for `DesiredMethodLimit` |
| 600 | 14,571 B | 13,666 B | over `HugeMethodLimit`: interpreted |

`lowering` compares the fork's configurations: `default` (what `MachineFactoryCompiler` does on
this JVM: unsigned compare fusion on), `unsigned-off`, and `branchless` (branchless booleans on).
The setup checks the compiled result against the interpreter.

## Results

JMH, 2 forks × 5 × 1 s after 5 × 1 s warmup (`jmh-fork.json`):

| sites | default | unsigned-off | branchless |
| --- | ---: | ---: | ---: |
| 320 | 14.03 ± 0.12 µs | 28.00 ± 0.27 µs | 9.26 ± 0.24 µs |
| 600 | 1,175 ± 10 µs | 1,189 ± 13 µs | 1,058 ± 13 µs |

Upstream main against the fork on the same modules (`HotLoop`, fresh JVM per sample, 10
interleaved rounds, the whole run including compilation: 200,000 calls of the 320-site function,
4,000 of the 600-site one; `hotloop.csv`):

| module | upstream | fork | paired Δ | 95 % CI |
| --- | ---: | ---: | ---: | --- |
| 320 sites | 12,064 ms | 2,983 ms | −75.2 % | [−75.4, −75.0] |
| 600 sites | 6,963 ms | 4,703 ms | −32.6 % | [−32.8, −32.0] |

[inlining-at-8000-bytes.md](inlining-at-8000-bytes.md) explains the 320-site gap: upstream, C2
refuses 97 % of this method's helper calls with `size > DesiredMethodLimit`.

## Decision on branchless booleans (§3.2)

The benchmark favours branchless booleans (−34 % at 320 sites), but two of its eight sites are
value-context booleans, far more than in real code. On the split workloads of the fusion A/B
(exploratory, 10 interleaved fresh-JVM rounds, fork with and without
`-Dendive.compiler.branchlessBooleans=true`, `branchless-ab.csv`):

| workload | steady Δ | 95 % CI | compile Δ |
| --- | ---: | --- | ---: |
| Lua, 8 kB split | +2.0 % | [−2.4, +8.5] | +1.3 % |
| SQLite, 8 kB split | −0.1 % | [−3.0, +0.7] | +1.2 % |
| QuickJS, 8 kB split | +1.2 % | [+0.0, +2.3] | +1.7 % |

No gain, slightly slower compilation, 0.2–0.4 % more bytecode: branchless booleans stay **off**
by default. Unsigned compare fusion, which halves the 320-site time and is size-neutral, stays on
where `MachineFactoryCompiler` may use it.
