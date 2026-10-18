# Upstream PR package (fork plan §2 and §4)

Status 2026-10-18. Every branch below is pushed to the fork (`origin`,
`github.com/Bernhard2000/endive`), based on upstream main `1580f534`, and was built and tested
locally as listed. **None is opened yet.** The Bytecode Alliance
[AI Tool Use Policy](https://github.com/bytecodealliance/governance/blob/main/AI_TOOL_POLICY.md)
says automated agents must not act in project spaces without human approval, and contributors
must read and review all generated code and text before asking for review. So the author
reviews each branch and opens the PR; the descriptions below are drafts to rewrite in their own
words.

## Before opening any of them

1. **Commit trailers.** The policy forbids listing tools as co-authors. Every commit on these
   branches still carries a `Co-Authored-By: Claude …` trailer. Replace it (optionally) with
   `Assisted-by: Claude Code`, on a fresh branch so the pushed one stays as a record:

   ```sh
   b=fix/many-params-abi            # repeat per branch
   git switch -c "$b-v2" "$b"
   git rebase upstream/main --exec \
     'git commit --amend --no-edit -m "$(git log -1 --format=%B | sed -E "s/^Co-Authored-By: Claude[^<]*<noreply@anthropic.com>$/Assisted-by: Claude Code/")"'
   git push -u origin "$b-v2"
   ```

   (The agent that prepared this package was not allowed to rewrite the history itself.)
2. `git fetch upstream main` and rebase if main moved; rerun the checks listed per PR
   (`compiler/docs/upstream-pr/TODO.md`, "Environment notes", for the local traps).
3. Open from the fork: `gh pr create --repo bytecodealliance/endive --base main --head
   Bernhard2000:<branch> --title "…" --body-file <file>`.

## Order

| # | branch | commits | value | checks run locally |
| --- | --- | --- | --- | --- |
| 2.1 | `fix/many-params-abi` | `43786182` | correctness: verifier failures, silently nulled references, invalid descriptors | JDK 21: compiler 40, compiler-tests 34,110, machine-tests 249, wabt 6 |
| 2.2+2.3 | `fix/memcopy-workaround-property` | `33b0aeba`, `88ed85b3` | bug: NPE with `-Dendive.memCopyWorkaround=true` on JDK 18+; robustness for `gt_u`/`le_u` | JDK 21: runtime 98, compiler 40, compiler-tests 34,111 + forced-property execution, machine-tests 228, wabt 6 |
| 2.4 | `pr/fuse-branch-conditions` | `e4105edf`, `3ba75b28` | performance | see `PR_DESCRIPTION.md`; additionally **JDK 11 (Temurin 11.0.32.1)**: compiler 48, wabt 6, compiler-tests 34,110, machine-tests 228 |
| 4 | `fix/build-sharp-edges` | `75249a83`, `1ec98224`, `a7034aba` | build hygiene | wasm-tools build with the versioned file; ApprovalTest with a stale approved file |
| 2.5 | `fix/wide-multivalue-return` | `9d805fcf` | correctness: `bad parameter count 257` on valid modules | JDK 21: compiler 40, compiler-tests 34,113, machine-tests 228, wabt 6 |
| 2.6 | `perf/switch-encoding` | `e4fb4d50` | size and dispatch speed | JDK 21: compiler 40, compiler-tests 34,114, machine-tests 228, wabt 6; JMH compiles |
| 2.7 | `pr/leb128-spec-reader` | `83c5c3f0` (rebased copy of `fix/leb128-spec-reader`) | maintainability, only if wanted | JDK 21: wasm 34, runtime 96, runtime-tests 62,246 (incl. `binary-leb128.wast`), compiler-tests 34,110, machine-tests 228 |
| — | discussion | — | unsigned `i32` fusion opt-in (§3.4) | implemented on the fork only |

2.1, 2.2 and 2.4 first; 2.5 and 2.6 after the maintainers know the contributor.

---

## 2.1 `fix/many-params-abi` — "Compiler: fix the boxed-argument ABI for functions with many parameters"

**Summary.** Functions whose parameters do not fit a JVM descriptor receive them boxed in a
`long[]`. That path had five connected bugs:

- declared locals were not zero-initialized (the loop mixed Wasm local indices with the JVM
  parameter count), so a local read before its first write failed verification and the whole
  function group could not be loaded;
- GC references and `externref` arguments were replaced by `null`, silently. Boxed signatures
  with object references now take an `Object[]` next to the `long[]`, forwarded through direct
  calls, `call_indirect`, `call_ref`, host import stubs, cross-module bridges and interpreter
  fallback;
- `call_indirect`/`call_ref` stubs append four JVM parameters, so 252- or 253-slot types produced
  256/257-slot descriptors (`IllegalArgumentException: bad parameter count`). Arguments are now
  boxed above **251** slots (previously 253), which fits every generated signature. This moves
  types with 252 and 253 slots onto the array path; it is a correctness trade-off, not a speed
  claim;
- boxed `call_indirect` boxed the table index with the arguments, and boxed `call_ref`
  overwrote the saved funcref with the first argument; both now live in a temp slot above the
  arguments;
- chunked `call_indirect` dispatch loaded arguments by parameter index instead of JVM slot,
  breaking `i64`/`f64` parameters.

**Tests.** `ManyParamsAbiTest` (machine-tests) runs interpreter, compiler and selected fallback
configurations over five new fixtures: default locals, non-null GC and host references, slots
251–254, mixed widths, chunked dispatch and cross-module calls. The lots-of-args approval
template is updated for the new `Object[]` parameter and shifted slots.

**Note for review.** `ApprovalTest.verifyLotsOfArgs.approved.txt` is generated from the
template and gitignored; a stale local copy hides template changes (fixed separately in
`fix/build-sharp-edges`).

## 2.2 + 2.3 `fix/memcopy-workaround-property` — "Fix -Dendive.memCopyWorkaround=true on Java 18+, and cover i32.gt_u/le_u"

**Summary.** `Shaded` enables the C2 workaround (JDK-8376400) when
`-Dendive.memCopyWorkaround=true` is set, on any Java version, and generated code then calls
`MemCopyWorkaround.memoryCopy`/`i32_ge_u`/`i32_lt_u`. `MemCopyWorkaround` only initialized the
functions behind those methods when its own Java version check said so, so with the property
set on Java 18+ every call threw a `NullPointerException`. It now applies the same rule as
`Shaded` for the warm-up and always initializes the functions.

Second commit: `i32.gt_u` and `i32.le_u` use the same `Integer.compareUnsigned` pattern as
`ge_u`/`lt_u` but were called directly; they now go through the same `Shaded`/`MemCopyWorkaround`
path (new methods only, so older generated code keeps working). I have no JDK 11/17 reproduction
for these two opcodes; the change is by analogy, and can be dropped if you prefer evidence first.

**Tests.** `MemCopyWorkaroundTest` (the property rule, helpers usable on any Java version);
`MemCopyWorkaroundForcedTest` runs compiled `memory.copy` and all four unsigned compares with
the property set, in its own surefire execution (the setting is read once per JVM). Without the
fix it fails with the NPE.

## 2.4 `pr/fuse-branch-conditions`

Use [PR_DESCRIPTION.md](PR_DESCRIPTION.md), with these updates:

- add the JDK 11 result above to "Tests" (TODO item 7: Java 11 now checked locally);
- the "AI assistance" section must not mention `Co-Authored-By` trailers (see "Before opening");
- optional evidence for reviewers: [inlining-at-8000-bytes.md](../inlining-at-8000-bytes.md)
  counts C2 refusing 97 % of the helper calls (`size > DesiredMethodLimit`) in a 7.8 kB
  generated method upstream; and `BenchmarkLargeMethod` (fork, `7ef91208`) is a JMH case
  upstream's Perf CI could use (TODO item 8). Both are fork-side and not in the branch.

## 4 `fix/build-sharp-edges` — "Build: three sharp edges"

1. **wasm-tools download keyed on its version.** `inlay-maven-plugin` skips fetching whenever
   the output file exists and `wkg.lock` has an entry, without a digest check, so a
   `wasm-tools.wasm` left in `target/` by an older `wasm-tools.version` survives a version bump
   (seen: "function 4725 exceeds the Java method size limits" from a 2025 download on current
   main). The file is now `target/wasm-tools-${wasm-tools.version}.wasm`. (The same skip for
   `redline/cranelift_bridge.wasm` is intentional and documented in CONTRIBUTING; untouched. A
   digest check in inlay itself would fix both; worth an issue there.)
2. **Lots-of-args approval file rendered on every run.** It is generated from the committed
   Velocity template and gitignored; rendering only when missing let a stale copy from another
   branch fail (or hide) template changes.
3. **Spotless formatting of `android-tests/device-tests/build.gradle.kts`.** The current ktfmt
   adds a trailing comma on every build of main, leaving the tree dirty.

## 2.5 `fix/wide-multivalue-return` — "Compiler: pack wide multivalue results inline"

**Summary.** A function returning several values calls a generated `value_xxx` method that
takes every result as a parameter. For tuples wider than 255 JVM slots (one `i32` and 128
`i64`: 257 slots) that descriptor is invalid and compiling a valid module fails with
`IllegalArgumentException: bad parameter count 257`. Such tuples are now packed inline from
temporary locals, with the same `long[]`/`Object[]` representation; smaller tuples keep the
helper.

**Tests.** `WideMultiValueReturnTest` (compiler-tests): 127/128/130 `i64` results (255 slots is
the widest that still uses the helper), a mixed `i32`/`f32`/`f64`/`i64` tuple with NaN payloads,
and a wide tuple with an `externref` whose identity must survive. All three fail on main with
`bad parameter count 256/257/258`.

## 2.6 `perf/switch-encoding` — "Compiler: choose a compact switch encoding for br_table"

> **On hold (2026-10-18).** The size-first choice makes `rustvm-base` 12.6 % slower: a sparse
> 160-entry table becomes a `lookupswitch` in a method HotSpot interprets, so each dispatch is a
> binary search. Add a speed-aware threshold and re-measure before opening this PR
> ([workload-ab-2026-10-18.md](../workload-ab-2026-10-18.md)).

**Summary.** `br_table` always became a `tableswitch` (4 bytes per entry). Large sparse tables
(thousands of entries for one common target, typical of interpreters and of function splitting)
are now encoded as the smaller of: a `lookupswitch` without the entries equal to the default;
for tables of at least 8000 bytes, a `lookupswitch` whose default is the most frequent target,
guarded by an unsigned bounds check that sends out-of-range selectors (including negative ints,
which are large unsigned values in Wasm) to the Wasm default; the `tableswitch`; or a pop and
jump when every entry is the default (also the valid empty `br_table`, where `tableswitch` would
have `high < low`).

**Out-of-range semantics.** Wasm selects the default for any selector `>= n` as an unsigned
value. `tableswitch` and a `lookupswitch` keyed on `0..n-1` both map every other int (negative
included) to their default, so they are exact when their default is the Wasm default. Only the
dominant-target variant changes the JVM default, and that variant first tests `sel < 0` and
`sel >= n` explicitly.

**Evidence.** CPython's `_PyUnicode_ToNumeric` shrinks from 65,469 to 47,793 bytes (−27 %), with
identical results for all 1,114,112 code points. A synthetic 4,096-entry table with three rare
targets (`BenchmarkBrTableDispatch`, added) went from 136 to 31 ns/op locally (two forks per
compiler, laptop; please treat as exploratory and rerun).

**Tests.** `BrTableEncodingTest`: which encoding is chosen, every selector from −2 to n+2 plus
`MIN_VALUE`/`MAX_VALUE` against the interpreter, empty/uniform/dense tables, and that a
backward `br_table` still checks interruption.

## 2.7 `pr/leb128-spec-reader`

Only if maintainers want it: one `readLeb128(buffer, bits, signed)` instead of three loops; no
behaviour change claimed, no performance claim. Rebased copy of `fix/leb128-spec-reader`
(cherry-pick was clean). Removes public helpers that have no in-tree callers.

---

## Discussion draft: opt-in unsigned `i32` fusion (§3.4)

To post as an issue (or a comment on the fusion PR) **before** proposing code:

> After branch-condition fusion, the largest group of comparisons still lowered to a helper
> call plus a 0/1 test is unsigned `i32` (QuickJS/Javy: ~4,000 sites, .NET: ~2,500, Go: ~1,100
> in `wasm-corpus`). Fusing them needs `Integer.compareUnsigned` or a `MIN_VALUE` bias, which is
> what JDK-8376400 miscompiles on Java 11–17. Would you accept it as an option?
>
> - `Compiler.Builder.withUnsignedCompareFusion(boolean)`, off by default;
> - `MachineFactoryCompiler` turns it on when the running JVM is not affected, with the same
>   check `MemCopyWorkaround` uses (Java 18+, property not forcing the workaround);
> - the build-time compiler, Maven plugin and CLI only expose it explicitly, documented as
>   "the generated classes need Java 18+".
>
> Emission would be `Integer.compareUnsigned` + `if<cond>`, the same size as today's call +
> `ifne`, and a C2 intrinsic on Java 21. A fork implementation with differential tests over all
> fixtures and option combinations exists (`Bernhard2000/endive@9e613f66`).

## Not proposed (yet)

- The fork's `NarrowingPeephole` and JDK-intrinsic emission (`17542744`): could be a small PR
  later (Go: −4.6 % bytecode, 16,615 → 585 `I64_EXTEND_I32_U` calls).
- The analyzer typing fixes from `50a7c320` (`select_t` result type, declared types at scope
  exits) are bug fixes on their own (the cast source hint after `select_t` is wrong on main) and
  could go upstream before `ref.eq` fusion.
