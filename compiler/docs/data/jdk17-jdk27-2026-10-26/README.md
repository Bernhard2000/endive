# Data: Java 17 and Java 27 validation, 2026-10-26

Evidence for [jdk17-jdk27-validation-2026-10-26.md](../../jdk17-jdk27-validation-2026-10-26.md).
Fork `c5219baf`, upstream `bce75ae6`. The scripts keep the absolute paths of the machine they ran
on (`/home/bernhard/Dokumente/MasterThesis/...`); the workloads come from the thesis repository
(`gitlab/experimental`), as in [../workload-ab-2026-10-18/](../workload-ab-2026-10-18/).

| path | content |
| --- | --- |
| `tests/wt-jdk17.ci.log.gz`, `tests/wt-jdk27.ci.log.gz` | Maven logs of `scripts/mvn-fork.sh install -pl compiler-tests,machine-tests,dircache -am` on Temurin 17.0.21 and 27.0.1.0.2, each in its own detached worktree of `c5219baf` |
| `tests/wt-jdk17.fusion-forced.log.gz` | `compiler-tests` and `machine-tests` on 17.0.21 with unsigned compare fusion forced on |
| `repro/results.txt` | outcomes of OpenJDK's `TestFoldedIfsWrongReexec` per JDK and flag set, and of the three Endive probes below |
| `repro/repro.wat`, `repro/repro.wasm`, `repro/Repro.java` | Wasm port of the reproducer's `test1`, run through `MachineFactoryCompiler` (`java -cp … Repro repro.wasm`; `repro.wasm` is the assembled `repro.wat`) |
| `repro/HelperRepro.java`, `repro/ShapeRepro.java` | Java ports through Endive's helpers (`-Dmode=0..3`) and six trigger shapes (`-Dshape=0..5`) |
| `stress/Wat2WasmStress.java`, `stress/run-matrix.sh`, `stress/matrix.log`, `stress/out/` | build-time-compiled `wat2wasm` stress matrix, 25 iterations × 3 runs per configuration |
| `campaign/arms.sh`, `campaign/campaign.sh` | workload A/B: arms and runner (`campaign.sh 10`) |
| `campaign/results/<cell>/` | per arm: one JSON line per round (`<arm>.jsonl`), stderr, the round orders, the module hash, and the guest output of **every** sample (`outputs/`, 50 per cell) |
| `campaign/summarize.py`, `campaign/summary.md` | paired ratios with bootstrap CIs and the output check; `python3 summarize.py` reproduces `summary.md` |
| `wat2wasm/wat2wasm-ab.sh`, `wat2wasm/results/`, `wat2wasm/summarize_wat2wasm.py`, `wat2wasm/summary-wat2wasm.md` | `wat2wasm` timing per arm; `wat2wasm-ab.log` shows the failed first upstream rounds (missing classpath file) and their rerun |

OpenJDK's `TestFoldedIfsWrongReexec.java` is not copied here (GPLv2 with Classpath Exception);
it is `test/hotspot/jtreg/compiler/c2/TestFoldedIfsWrongReexec.java` in the OpenJDK sources of
the JDK-8376400 fix. It was compiled per JDK with that JDK's `javac` and run with plain `java`.
