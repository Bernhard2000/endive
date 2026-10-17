# Upstream PR: what is left to do

PR branch: `pr/fuse-branch-conditions` (local only, not pushed), based on
`upstream/main` = `1580f534` (fetched 2026-10-17 from `https://github.com/bytecodealliance/endive`,
remote `upstream`).

| commit | content |
| --- | --- |
| `e4105edf` | Fork `f5225324` + `854e77ca` squashed: the original fusion with the `EnumOrdinal` suppression, so the commit builds under `-Werror` |
| `3ba75b28` | Fork `5a02a7dd`: the extensions |

Both commits contain only `compiler/src`, `compiler/src/test` and `wasm-corpus` changes. None
of the fork's thesis docs (`compiler/docs/*`, `FORK_CHANGES.md`), the ABI fix or the
split-function work are included. Both cherry-picks applied without conflicts. The PR
description draft is [PR_DESCRIPTION.md](PR_DESCRIPTION.md).

## Done

- [x] Rebased onto current upstream main. Full build with `-am` passes; `compiler` (Error
      Prone `-Werror`, Checkstyle, Spotless check), `compiler-tests` (34,120 spec tests),
      `machine-tests` (247) and `wabt` (6) pass on JDK 21.
- [x] Fork branch: `compiler`, `wabt` and `machine-tests` pass on Temurin 17.0.20.1, the JDK
      range of the C2 bug.
- [x] Static size and helper-call measurements against upstream main
      ([evidence](../data/branch-condition-fusion-2026-10-17/)).
- [x] Javadoc rationale for not fusing unsigned `i32` corrected (JIT audit recommendation 2).

## Must do before opening

1. **Decide the PR shape.** Options:
   - (a) one PR with both commits. This is the current branch. Upstream squash-merges, so the
     split only helps review.
   - (b) two PRs: first `e4105edf` alone (small, easy to review), then `3ba75b28` on top.
     Likely easier to get accepted, given CONTRIBUTING's "simple code … measurable benefits"
     philosophy.
   - (c) split commit 2 further: numeric (constants, unsigned `i64`, Go booleans) and GC null
     tests. These are independent in code, apart from `BranchCondition`.
2. **Re-fetch upstream and rebase** right before opening:
   `git fetch upstream main && git rebase upstream/main pr/fuse-branch-conditions`, then rerun
   the checks below.
3. **Rerun the local checks after the rebase.** Watch out for the three local traps in
   "Environment notes" below.
   ```sh
   ./mvnw install -Dquickly -pl compiler-tests,machine-tests,wabt -am
   ./mvnw install -pl compiler,compiler-tests,machine-tests,wabt
   ```
4. **AI tool policy.** (Read 2026-10-18: tools must not be co-authors; agents must not open
   PRs without human approval. See [OPEN_PRS.md](OPEN_PRS.md) "Before opening".) CONTRIBUTING points to the
   [Bytecode Alliance AI Tool Use Policy](https://github.com/bytecodealliance/governance/blob/main/AI_TOOL_POLICY.md).
   Read it and adjust the "AI assistance" section of the PR description and the commit
   trailers to match. The first commit keeps the original `Co-Authored-By: Claude Opus 5`
   trailer. The fork commit's `Claude-Session:` URL line was dropped from the PR copy.
5. **Proofread and own the description.** Check that every claim in PR_DESCRIPTION.md
   matches the final branch. Re-measure if the rebase changes anything in `compiler/`.
6. **Push the branch to your fork and open the PR** (from your fork, per CONTRIBUTING):
   ```sh
   git push -u origin pr/fuse-branch-conditions
   gh pr create --repo bytecodealliance/endive --base main \
       --head Bernhard2000:pr/fuse-branch-conditions \
       --title "Compiler: fuse conditions into the branches and selects that consume them" \
       --body-file <(sed '1,/^---$/d' compiler/docs/upstream-pr/PR_DESCRIPTION.md)
   ```
   (`PR_DESCRIPTION.md` exists only on the fork branch; copy it out first or run the command
   from the fork branch.)

## Should do (likely review requests)

7. **Run CI that could not run locally.** Upstream CI covers Java 11/17/21/25 × Linux, macOS,
   Windows and ARM, plus Android API 28/35. Not run locally:
   - **Java 11**: done 2026-10-18 on Temurin 11.0.32.1: `compiler` (48), `wabt` (6),
     `compiler-tests` (34,110) and `machine-tests` (228) pass.
   - **Windows + Java 11/17**: that is where the C2 bug used to show up as flaky failures
     (see #134: "20 parallel Windows+Java 11 CI runs"). Consider asking maintainers for
     repeated runs, or running the workflow on your fork several times.
   - **Android** (minSdk 28; `Long.compareUnsigned` exists since API 26).
8. **Runtime evidence.** Local workload timing is done
   ([branch-condition-fusion-benchmarks.md](../branch-condition-fusion-benchmarks.md)): 4–10 %
   faster wherever the hot code is compiled, and Endive's JMH shows no change. Still to do:
   - decide whether to cite the local numbers in the PR. They come from a laptop with the
     `ondemand` governor; a repeat on a fixed runner would be stronger;
   - the Perf workflow (`.github/workflows/perf.yaml`) runs the same JMH suite on the PR and
     will likely show "no change", so explain that in the PR (done in the draft);
   - consider adding to `jmh/` a benchmark that exercises a large (> 8000 B) generated method
     with many comparisons, so upstream's own Perf CI can see the effect. A small WASI
     workload such as Lua would also do. The audit's open recommendation 4 (`-XX:+PrintInlining` count of
   `size > DesiredMethodLimit` for `OpcodeImpl.I32_*` before and after) would be the direct
   evidence.
9. **Fixture provenance.** `branch_conditions.wat` and `branch_conditions_const.wat` were
   generated by throwaway Python scripts that are not committed. The `.wasm` files were built
   with wabt 1.0.40 `wat2wasm`; the existing `branch_conditions.wat.wasm` reproduces byte for
   byte. `ref_conditions.wat.wasm` was built with Endive's own `run.endive.tools.wasm.Wat2Wasm`
   (wasm-tools 1.256.0), because wabt 1.0.40 lacks GC text support. That tool reproduces the
   existing `br_on_null.wat.wasm` byte for byte. Either commit a generator, or state this in
   the PR. Ask maintainers which tool they want for `wasm-corpus` binaries.
10. **Snapshot size.** `ApprovalTest.verifyRefConditions.approved.txt` is 2,687 lines.
    Maintainers may prefer a smaller fixture for the approval test (for example only the
    `struct_*` and `func_*` `br_on_null` / `is_null_if` functions), keeping the full
    fixture for the differential test only.
11. **Internal opcode removal.** `BR_ON_NULL_CHECK`, `BR_ON_NON_NULL_CHECK` and
    `BR_ON_CAST_FAIL_CHECK` were removed and `DUP` added. Check that no downstream code
    (Redline, the build-time compiler, external forks) uses them. Inside the repository,
    nothing outside `compiler/` referenced them.

## Not in this PR (possible follow-ups, mention if asked)

- Unsigned `i32` fusion for variable operands and arbitrary constants. This is the largest
  remaining gap: .NET still has 2,481 sites, QuickJS 4,003, Go 1,078. It is only safe on JVMs
  without JDK-8376400, so it would need an explicit opt-in, or a check of the executing JVM in
  `MachineFactoryCompiler`. That check cannot work for build-time compilation.
- `ref.eq` fusion: needs `select`/`select_t` to type their result by the annotation (the
  analyzer currently uses the second operand's type) and an `i31`-exclusion predicate.
- `I64_EXTEND_I32_U` is a helper call (16,615 call sites in `main.go.wasm`). An intrinsic
  `I2L; LDC 0xFFFFFFFFL; LAND` would be a small separate PR.
- Pre-existing issues from the JIT audit: the `-Dendive.memCopyWorkaround=true` NPE on
  JDK 18+, and `I32_GT_U`/`I32_LE_U` not being covered by the workaround.

## Environment notes (local traps hit while preparing the branch)

- `wasm-tools/target/wasm-tools.wasm` is only downloaded if missing. A stale Oct-2025 copy
  made `wasm-tools` fail with "function 4725 exceeds the Java method size limits", on upstream
  main too. The current download is now in place; the stale file is parked as
  `wasm-tools.wasm.stale-2025`.
- A local `redline/cranelift_bridge.wasm` (built 19 Sep) shadows the pinned bridge and breaks
  Redline compilation on upstream main. It is restored for the fork branch; move it aside when
  building `pr/fuse-branch-conditions`.
- `compiler/src/test/resources/run/endive/approvals/ApprovalTest.verifyLotsOfArgs.approved.txt`
  is gitignored and generated on first run. The fork's copy (with the ABI changes) makes
  `verifyLotsOfArgs` fail on upstream-based branches. Move it aside there.
- Building on upstream main reformats `android-tests/device-tests/build.gradle.kts` (Spotless
  adds a trailing comma). Do not commit that change.
- Installing either branch overwrites the `999-SNAPSHOT` artifacts in `~/.m2`, which is also
  where the thesis harness resolves the vendored Endive. Build fork branches with
  `scripts/mvn-fork.sh` (same arguments as `./mvnw`), which uses its own local repository
  `~/.m2/repository-endive-fork`:
  `scripts/mvn-fork.sh install -Dquickly -pl compiler-tests,machine-tests,wabt -am`.
