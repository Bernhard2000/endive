# Branch-condition fusion: local runtime benchmarks

Run: 2026-10-17, 18:45–19:42 local time. This was a single session on a laptop, so treat
the numbers as exploratory timing evidence, not fixed-runner thesis evidence.
Companion to [branch-condition-fusion-extensions.md](branch-condition-fusion-extensions.md),
whose §8 covers the static size and helper-call effect. Raw data and scripts:
[data/branch-condition-fusion-2026-10-17/runtime-benchmarks/](data/branch-condition-fusion-2026-10-17/runtime-benchmarks/).

## Question

Does the upstream PR branch run real Wasm workloads faster than upstream Endive? The question
covers both unsplit modules and modules split by the thesis' Binaryen `FunctionSplitting`
pass.

| arm | Endive revision |
| --- | --- |
| **upstream** | `bytecodealliance/endive` main `1580f534` |
| **PR** | `pr/fuse-branch-conditions` `3ba75b28` = upstream main + the original fusion (`e4105edf`) + the extensions |

Both arms were built from their own worktrees with `./mvnw package -Dquickly`. Each runs from
its own `target/*.jar` files, not from `~/.m2`, so neither can pick up the other's classes.
Only the compiler differs between them.

## Summary

| workload | where the hot code runs | steady-state Δ PR vs upstream (95 % CI) |
| --- | --- | --- |
| Lua, unsplit | compiled | **−5.5 %** [−6.9, −4.3] |
| Lua, 8 kB split | compiled | **−4.1 %** [−7.0, −0.9] |
| SQLite, 8 kB split | compiled | **−9.5 %** [−11.1, −7.7] |
| QuickJS, 8 kB split | compiled | **−6.4 %** [−8.6, −5.3] |
| SQLite, unsplit | one > 64 KB function interpreted | −0.2 % [−2.3, +2.3], n.s. |
| QuickJS, unsplit | one > 64 KB function interpreted | +1.0 % [−2.6, +2.6], n.s. (n = 5) |
| Endive JMH (factorial, sieve, wat2wasm, dispatch) | small compiled methods | all within ±4 %, inside fork-to-fork noise |

Wherever the hot code is JIT-compiled bytecode, the PR is **4–10 % faster** in steady state.
Where the hot code is a function too large for the JVM, Endive falls back to its interpreter,
and fusion cannot help. Splitting is what moves that code back into compiled methods, so the
two changes are complementary. The largest gain is on a split workload (SQLite −9.5 %).
Runtime compilation gets slightly slower: +2 to +2.5 % on the unsplit modules, about 20 ms.
Endive's own microbenchmarks show no effect.

## Setup

**Machine:** AMD Ryzen 7 7840HS (8 cores, 16 threads), Linux 7.2.8, OpenJDK 21.0.12.1
(`/usr/bin/java`). The CPU governor was `ondemand` with boost on; neither could be changed
without root. All measurement JVMs were pinned with `taskset -c 4-7`.

**Harness.** The engine A/B runner is new (`engine_ab.sh`). It reuses the thesis repository's
`EndiveRun.java` (WASI runner on `MachineFactoryCompiler`, i.e. Endive's runtime compiler)
and the L1 statistical protocol (`endive-benchmark/wasi_exec/l1_protocol.sh`):

- each sample is a **fresh JVM**: parse, runtime compile, then `warmup` + `reps` executions of
  `_start`;
- the two arms run in a **seeded random order within every round** (`BENCH_ORDER=permuted`,
  seed `20261017`);
- the verdict is the **paired per-round ratio** PR/upstream, median with a bootstrap 95 % CI
  (10,000 resamples). It is *significant* only when the CI excludes 1. The L1 tables also
  report the unpaired bootstrap CI of the median delta, which agrees in every case.

Three metrics are taken per sample: `exec_ms_median` (steady), `first_exec_ms` (first
execution, which includes JIT warm-up), and `compile_ms` (parse + runtime compile).

**Workloads.** These are the thesis corpus contracts from `run_corpus_split_matrix.sh`:

| workload | module | guest args | rounds | warmup / reps per JVM |
| --- | --- | --- | ---: | --- |
| lua | `lua_wasi.wasm` (Lua 5.4) | `2 a` | 15 | 1 / 3 (split: 2 / 5) |
| sqlite | `sqlite_wasi.wasm` (built-in workload) | – | 15 split / 10 unsplit | 1 / 3 (unsplit: 0 / 1) |
| quickjs | `qjs_wasi.wasm` | `1` | 10 split / 5 unsplit | 1 / 2 (unsplit: 0 / 1) |

The rounds and reps for unsplit SQLite (15 s per execution) and unsplit QuickJS (100 s per
execution) were reduced to fit the time budget. QuickJS unsplit therefore has only 5 rounds.

**Split modules** come from `run_corpus_split_matrix.sh prepare` with the canonical
*endive-throughput* recipe, using thesis repository `e60e5e1de`'s `wasm-opt`. The recipe is:
a 3-run edge profile under Node, the `jvm` size metric, an 8 kB limit, post-split cleanup and
`br_table` arm grouping. Every split passed the harness gates. G1: wasmtime output identical
to the pristine module. G2: methods over 8 kB / over 64 kB according to the size oracle went
SQLite 19 → 5 / 1 → 0, QuickJS 12 → 5 / 1 → 0, and Lua 5 → 0 / 0 → 0. SHA-256 of every
module: `modules.sha256`.

Both arms run identical module bytes. This benchmark compares engines, not split against
unsplit.

**Endive JMH.** Upstream's `jmh/` suite was built in each worktree (`-pl jmh -am`; the
`wat2wasm` used by `BenchmarkWat2Wasm` is therefore build-time compiled by each arm's own
compiler). Run: `-f 1 -wi 3 -w 2s -i 4 -r 2s`, three forks per arm in the order A B B A A B,
pinned to cores 4–7. That is shorter than JMH defaults. The first attempt at factorial and
sieve failed because those benchmarks open `wasm-corpus/...` by a relative path; they were
rerun from each worktree's root (`jmh2.sh`).

## Results

### Steady-state execution (median of the in-process reps)

| workload | n | upstream ms | PR ms | Δ (paired median) | 95 % CI | significant |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| lua-base | 15 | 1,779.4 | 1,679.4 | −5.5 % | [−6.9 %, −4.3 %] | yes |
| sqlite-base | 10 | 15,323.7 | 15,345.1 | −0.2 % | [−2.3 %, +2.3 %] | no |
| quickjs-base | 5 | 99,510.7 | 100,567.1 | +1.0 % | [−2.6 %, +2.6 %] | no |
| lua-split | 15 | 262.8 | 252.8 | −4.1 % | [−7.0 %, −0.9 %] | yes |
| sqlite-split | 15 | 1,887.2 | 1,708.5 | −9.5 % | [−11.1 %, −7.7 %] | yes |
| quickjs-split | 10 | 8,306.7 | 7,696.2 | −6.4 % | [−8.6 %, −5.3 %] | yes |

### First execution in a fresh JVM

| workload | n | upstream ms | PR ms | Δ (paired median) | 95 % CI | significant |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| lua-base | 15 | 1,816.1 | 1,727.1 | −4.8 % | [−7.7 %, −4.0 %] | yes |
| sqlite-base | 10 | 15,323.7 | 15,345.1 | −0.2 % | [−2.3 %, +2.3 %] | no |
| quickjs-base | 5 | 99,510.7 | 100,567.1 | +1.0 % | [−2.6 %, +2.6 %] | no |
| lua-split | 15 | 578.9 | 557.1 | −1.4 % | [−11.4 %, +0.0 %] | no |
| sqlite-split | 15 | 2,593.9 | 2,359.8 | −8.1 % | [−9.7 %, −6.3 %] | yes |
| quickjs-split | 10 | 8,389.2 | 7,805.7 | −6.9 % | [−7.8 %, −5.7 %] | yes |

For the unsplit SQLite and QuickJS runs (0 warm-up, 1 rep), first and steady are the same
sample.

### Parse + runtime compile

| workload | n | upstream ms | PR ms | Δ (paired median) | 95 % CI | significant |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| lua-base | 15 | 630.5 | 647.1 | +2.5 % | [+0.1 %, +3.7 %] | yes |
| sqlite-base | 10 | 1,683.0 | 1,709.8 | +2.2 % | [+0.3 %, +4.6 %] | yes |
| quickjs-base | 5 | 1,563.1 | 1,607.2 | +0.9 % | [−4.7 %, +5.7 %] | no |
| lua-split | 15 | 605.0 | 612.2 | −0.0 % | [−1.8 %, +2.4 %] | no |
| sqlite-split | 15 | 1,368.1 | 1,368.4 | +0.1 % | [−0.3 %, +1.2 %] | no |
| quickjs-split | 10 | 1,128.5 | 1,143.8 | +1.7 % | [−0.8 %, +3.9 %] | no |

### Endive JMH

| benchmark | params | unit | upstream | PR | PR vs upstream | fork spread upstream / PR |
| --- | --- | --- | ---: | ---: | ---: | --- |
| FactorialExecution.benchmarkCompiled | input=5 | ops/s | 1.904e8 | 1.960e8 | +3.0 % | 2.9 % / 1.4 % |
| FactorialExecution.benchmarkCompiled | input=1000 | ops/s | 1.117e6 | 1.110e6 | −0.6 % | 1.3 % / 2.6 % |
| FactorialExecution.benchmarkInt (interpreter control) | input=5 | ops/s | 1.474e6 | 1.477e6 | +0.2 % | 0.8 % / 1.2 % |
| FactorialExecution.benchmarkInt (interpreter control) | input=1000 | ops/s | 1.135e4 | 1.126e4 | −0.7 % | 1.0 % / 0.9 % |
| SievePrimes.benchmark | ByteArrayMemory | ops/s | 61.37 | 61.67 | +0.5 % | 0.5 % / 14.8 % |
| SievePrimes.benchmark | ByteBufferMemory | ops/s | 34.35 | 35.42 | +3.1 % | 0.9 % / 3.5 % |
| Wat2Wasm.benchmarkAot | – | ops/s | 19.95 | 19.88 | −0.4 % | 2.5 % / 6.7 % |
| DispatchChunkSize.dispatch | targetFunc=0 / 999 / 1999 | µs/op | 0.00677 / 0.00676 / 0.00694 | 0.00704 / 0.00695 / 0.00687 | −3.8 / −2.7 / +1.0 % | up to 7.8 % |

Three forks per arm are too few for a CI. Every difference is smaller than the spread between
forks of the same arm, so no effect is detectable.

## Interpretation

1. **The speed-up comes from compiled code.** In all four workloads whose hot code is
   compiled, the PR is 4–10 % faster and the CIs are well clear of zero. This matches the
   mechanism. A fused branch removes an `invokestatic` and a 0/1 materialization from the
   generated method. That matters wherever C2 does not inline the helper: in large methods
   (over 8,000 bytes, or near C2's inlining budget), and while code still runs in the
   interpreter or C1. Unsplit Lua has five methods over 8 kB, which is where such
   un-inlined helpers would be.
2. **Unsplit SQLite and QuickJS are neutral, as expected.** Each has one function larger than
   the JVM's 64 KB method limit (`sqlite3_step`, function 77, and QuickJS function 31). Both
   arms print `using interpreted mode for WASM function index` for it, and that function
   dominates the run. Endive's interpreter is unchanged by the PR, so there is nothing for
   fusion to improve. The compiled remainder runs too little to show at this sample size.
3. **Fusion complements splitting.** After an 8 kB split, the formerly interpreted hot code
   becomes many compiled methods, and fusion speeds those up further. On SQLite, splitting
   takes a run from 15.3 s to 1.89 s on upstream; the PR then saves another 9.5 % (1.71 s).
   This fits the static picture: fusion shrinks methods by 2–4 % and removes most comparison
   helper calls, which leaves more of C2's inlining budget for the rest of the method.
4. **Compile time.** Runtime compilation is up to +2.5 % slower on the unsplit modules
   (+17 ms on Lua, +27 ms on SQLite). The split modules show no significant change. This
   cost is paid once per instantiation and is small next to the execution gains here.
   Investigated later ([compile-time-regression.md](compile-time-regression.md)): it is a
   cold-JVM cost of the extra pass over, and copy of, every function's instruction list; warm
   compiles do not differ. The fork now runs the passes while the list is built.
5. **Endive's JMH suite does not show the effect.** Its compiled benchmarks are tiny
   functions. C2 inlines the comparison helpers in tiny functions and folds the 0/1 away, so
   fusion changes little after warm-up, which is what the static analysis predicted. The
   upstream Perf workflow, which runs this suite, will therefore probably report "no change".
   The workload numbers above are the better evidence for the PR.

## Limitations

- One laptop, one session, `ondemand` governor with boost on. The interleaved, paired design
  cancels slow drift but not every source of noise. Repeat on a fixed runner (kent-yuria1–3)
  before citing any of these numbers in the thesis.
- Small n for the expensive arms: unsplit QuickJS has 5 rounds and unsplit SQLite has 10.
- JDK 21 only. JDK 17, where C2 behaves differently around unsigned compares, was not timed.
- Runtime compiler (`MachineFactoryCompiler`) only. Build-time compiled classes contain the
  same bytecode, so the same steady-state effect is expected there, but that was not measured.
- Only three corpus workloads; Python, binjgb and Genesis were left out for time.
- Both arms contain the *original* fusion as well (PR = commit 1 + commit 2). These numbers
  measure the whole PR; they do not separate the extensions' share.

## Reproduce

```sh
B=<bench dir>                         # contains wt-A, wt-B (worktrees), splits/, results/
git worktree add $B/wt-A upstream/main
git worktree add $B/wt-B pr/fuse-branch-conditions
for w in wt-A wt-B; do (cd $B/$w && ./mvnw -q package -Dquickly -pl compiler,wasi,jmh -am); done
# cp-A.txt / cp-B.txt: wasm, runtime, log, wasi, compiler, annotations target jars + asm 9.10.1
# classes-A / classes-B: javac --release 21 -cp "$(cat cp-X.txt)" -d classes-X EndiveRun.java
cd <thesis repo>/endive-benchmark/wasi_exec
SPLIT_LIMITS=8 OUT_DIR=$B/splits ./run_corpus_split_matrix.sh prepare sqlite quickjs lua
$B/campaign.sh && $B/jmh2.sh && python3 $B/summarize.py
```
