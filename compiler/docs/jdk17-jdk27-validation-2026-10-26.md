# Java 17 and Java 27: tests, JIT bug check and workload A/B (local, 2026-10-26)

The audit fixes were validated on Java 21 only, leaving Java 11/17/25 to the new `Fork CI`
workflow, which has not run (FORK_CHANGES.md, section 11). This note records a local run on the
two Java versions that matter most for the fork's defaults: Java 17, the newest line that still
has the C2 bug JDK-8376400 and therefore runs with the workaround and without unsigned `i32`
compare fusion, and Java 27, the newest GA release, where the bug is fixed and the fusion is on.
Laptop measurements, so the timings are exploratory, like
[workload-ab-2026-10-18.md](workload-ab-2026-10-18.md). Scripts, logs and raw data:
[data/jdk17-jdk27-2026-10-26/](data/jdk17-jdk27-2026-10-26/).

| | revision |
| --- | --- |
| fork | `fuse-branch-conditions` `c5219baf` (engine code as of `8b4becfd`) |
| upstream | `bytecodealliance/endive` main `bce75ae6`, the fork's base |
| Java 17 | Temurin 17.0.21+9; old Temurin 17.0.7+7 for the reproducers |
| Java 27 | Temurin 27.0.1.0.2+1 |
| others (reproducers only) | OpenJDK 21.0.12.1 (distribution), Microsoft OpenJDK 25.0.4.1 |

## Summary

- **The fork's test suites pass on Java 17 and 27.** The `Fork CI` job's command,
  `install -pl compiler-tests,machine-tests,dircache -am`, run locally per JDK: every module
  builds, compiler specification suite 34,123 (64 skips), `machine-tests` 265, compiler unit
  66, runtime 98, `DirectoryCacheTest` 4, no failures or errors. On Java 17 the
  `compiler-tests` and `machine-tests` also pass with unsigned compare fusion forced on.
- **JDK-8376400 is not fixed on Java 17.** OpenJDK's own reproducer
  (`TestFoldedIfsWrongReexec`) still fails on 17.0.21, the latest 17u, with default flags; the fix
  was backported to 21.0.14 and 25.0.5 only. 21.0.12 and 25.0.4 fail it only with the test's
  first `@run` flags (`-XX:-OptimizeUnstableIf` and no tiered compilation); 27.0.1 passes both.
  So the fork's rule (workaround and no unsigned fusion before Java 18) stays.
- **No Endive-level reproduction.** A Wasm port of the reproducer, Java ports through Endive's
  helpers (`OpcodeImpl`, `MemCopyWorkaround`, `Shaded`) in six shapes, and 3 × 25 runs of the
  build-time-compiled `wat2wasm` (the module that first exposed the bug) give correct results on
  17.0.7 and 17.0.21, with the workaround on or off and with the fusion forced on. That is no
  evidence of safety: the bug needs a specific C2 shape, and the workaround stays.
- **Workload A/B: the fork is faster than upstream on both JDKs**, with the workaround on Java 17
  as shipped: steady state −5.1 to −10.8 % on Java 17 and −4.3 to −14.6 % on Java 27, all six
  cells significant; guest output identical across all 50 samples of every cell.
- **The workaround costs little on these workloads.** Turning it (and the fusion) off on Java 17
  changes steady state by −0.8 to +1.4 %, significant only for SQLite split (−0.8 %). The
  build-time-compiled `wat2wasm` is the exception: about 4 % faster with it off.

## Tests

| JDK | command | result |
| --- | --- | --- |
| Temurin 17.0.21 | `scripts/mvn-fork.sh install -pl compiler-tests,machine-tests,dircache -am` (own Maven repository `repository-endive-fork-jdk17`) | BUILD SUCCESS, 6:00 min |
| Temurin 27.0.1 | same, `repository-endive-fork-jdk27` | BUILD SUCCESS, 6:47 min |
| Temurin 17.0.21 | `compiler-tests` and `machine-tests` again, unsigned compare fusion forced on | BUILD SUCCESS: 34,123 (64 skips) and 265 |

Per-module counts, identical on both JDKs: wasm 36, runtime 98, annotations processor 8,
compiler 66, Redline API 18, WASI 11, wabt 6, wasm-tools 10, compiler-tests 34,123 (64 skipped)
plus the forced-workaround execution 1, dircache 4, machine-tests 265. On Java 17 the root POM's
profiles leave out Error Prone, Checkstyle and Spotless (`disable-error-prone`, active for
`[11,21)`, turns off the first two; Spotless is only in the `java21` profile); on Java 27 the
strict build runs and passes.
The interpreter specification suite (`runtime-tests`) is not part of the `Fork CI` job and was
not run here. Java 11 and 25 remain untested since the audit fixes.

## JDK-8376400 on current JDKs

OpenJDK's regression test for the bug,
`test/hotspot/jtreg/compiler/c2/TestFoldedIfsWrongReexec.java`, compiled per JDK and run with
its two `@run` lines:

| JDK | default flags | `-XX:-TieredCompilation -XX:-UseOnStackReplacement -XX:-BackgroundCompilation -XX:+UnlockDiagnosticVMOptions -XX:-OptimizeUnstableIf` |
| --- | --- | --- |
| Temurin 17.0.7 | **fails** | **fails** |
| Temurin 17.0.21 | **fails** | **fails** |
| OpenJDK 21.0.12.1 | passes | **fails** |
| Microsoft 25.0.4.1 | passes | **fails** |
| Temurin 27.0.1 | passes | passes |

Endive-level probes, on 17.0.7, 17.0.21 and 27.0.1, all correct:

| probe | what it runs | variants |
| --- | --- | --- |
| `Repro` + `repro.wat` | the test's `test1` as Wasm, compiled by the fork: an unsigned compare against 8 (lowered to a shift) and against 9 (helper) after a call that traps on `i >=u 16` | default; workaround off; workaround off + fusion on; fusion on |
| `HelperRepro` | the Java test with its unsigned compare routed through the raw `MIN_VALUE` bias, `OpcodeImpl`, `MemCopyWorkaround`, `Shaded` | `-Dmode=0..3` |
| `ShapeRepro` | six shapes of the trigger built from `OpcodeImpl` helpers and `Integer.compareUnsigned` | `-Dshape=0..5` |
| `Wat2WasmStress` | build-time-compiled `wat2wasm` on two large generated inputs, output hash compared every iteration | classes built without and with unsigned compare fusion; 17.0.21: both × workaround auto/off; 17.0.7: both × workaround off; 27.0.1: without × workaround auto/forced on, with × auto; 25 iterations × 3 runs each |

None of them reproduces the miscompilation on any JDK, including the raw bias on Java 17, which
does not reproduce it outside the exact shape of OpenJDK's test either. The Endive probes
therefore say nothing about safety without the workaround; the OpenJDK reproducer is what
decides the rule.

## Workload A/B

Six cells from the 2026-10-18 A/B (three Lua/SQLite, two AWFY Richards, binjgb), five arms, ten
rounds, fresh JVM per sample, seeded permuted arm order (`20261026`), `taskset -c 4-7`, both
Endive builds from their own worktrees. Arms: `up17`, `fork17` (Java 17, workaround on, as
shipped), `fork17noWA` (`-Dendive.memCopyWorkaround=false
-Dendive.compiler.unsignedCompareFusion=false`: the workaround's cost alone), `up27`, `fork27`.
Verdict: median of the paired per-round ratios with a bootstrap 95 % CI (10,000 resamples);
bold where the CI excludes 0.

Steady state (`exec_ms_median`):

| workload | fork vs upstream, Java 17 | workaround off vs on, fork, Java 17 | fork vs upstream, Java 27 | fork, Java 27 vs 17 |
|---|---|---|---|---|
| awfy-js-split-Richards | **−6.5 %** [−7.2, −4.2] | −0.6 % [−1.3, +0.4] | **−14.6 %** [−16.7, −13.7] | **−15.4 %** [−16.5, −14.4] |
| awfy-lua-split-Richards | **−5.6 %** [−11.1, −3.6] | +0.5 % [−1.3, +1.6] | **−9.4 %** [−11.1, −6.8] | **−4.0 %** [−5.6, −2.6] |
| binjgb-split | **−10.8 %** [−12.1, −6.0] | −0.8 % [−4.9, +4.0] | **−10.9 %** [−11.8, −10.6] | **−2.7 %** [−5.5, −0.4] |
| lua-base | **−5.1 %** [−8.1, −3.4] | +1.4 % [−1.2, +2.4] | **−4.3 %** [−7.1, −2.9] | −1.3 % [−1.8, +1.6] |
| lua-split | **−9.0 %** [−9.8, −7.6] | −0.4 % [−1.1, +0.4] | **−13.0 %** [−14.3, −11.6] | **−1.4 %** [−2.5, −0.1] |
| sqlite-split | **−9.3 %** [−10.0, −7.2] | **−0.8 %** [−1.7, −0.5] | **−10.7 %** [−11.0, −9.9] | **−3.0 %** [−3.7, −1.9] |

Runtime compilation (`compile_ms`) is +3.8 to +7.6 % against upstream on Java 17 and +2.4 to
+6.9 % on Java 27, the cold-JVM cost already known from the 2026-10-18 A/B. First-execution
times and the medians per arm are in the data directory's `summary.md`.

The Java 17 gains are in the range the 2026-10-18 A/B found on Java 21 (median −6.7 %), so the
fork's benefit does not depend on the unsigned fusion it disables there. On Java 27 the fork
gains more on QuickJS Richards and Lua split, where the fusion runs and C2 is newer.

**`wat2wasm`, build-time compiled** (`Wat2WasmStress`, 12 iterations per JVM, iterations 2–11,
3 rounds per arm, medians):

| input | up17 | fork17 | fork17noWA | up27 | fork27 |
|---|---|---|---|---|---|
| `bigWat(50000,0)` | 2660 ms | 2649 ms | 2534 ms | 2508 ms | 2482 ms |
| `bigWat(10,15000)` | 2280 ms | 2298 ms | 2208 ms | 2131 ms | 2132 ms |

All 15 runs produce one output hash per input. Fork and upstream are within 1 % on both JDKs;
turning the workaround off saves 3.9–4.3 % on Java 17. That module is the one place measured
where the workaround is visible, and it is still small next to the risk.

## Limitations

- One laptop (Ryzen 7 7840HS, `ondemand` governor, desktop session running), ten rounds per
  cell, six cells. Not fixed-runner evidence; the thesis does not cite these numbers.
- The `wat2wasm` A/B ran sequential rounds per arm, not a permuted fresh-JVM design per
  iteration, and its first round of the upstream arms failed on a missing classpath file and
  was rerun.
- Java 11 and 25 and other operating systems (where the bug was first seen, on Windows) remain
  for the `Fork CI` workflow and upstream CI.
