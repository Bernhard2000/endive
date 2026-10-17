# Branch-condition fusion: extensions

Follow-up to [branch-condition-fusion.md](branch-condition-fusion.md) (commit `f5225324`, the
original pass). Implemented in fork commit `5a02a7dd` ("Extend branch-condition fusion to
constants, unsigned i64 and null tests"), on top of `82f39301`. The upstream-PR copy of the
same change is `3ba75b28` on branch `pr/fuse-branch-conditions` (see
[upstream-pr/](upstream-pr/)).

The original pass fused a signed or floating-point comparison, plus any `i32.eqz` chain, into
the `if` / `br_if` / `select` that consumes it. This change widens what counts as a fusable
condition. Every rewrite stays a local peephole on the analyzer's instruction list: the
condition's producer must directly precede its consumer, so no control flow can enter between
them.

Contents:

1. [Summary](#1-summary)
2. [How the increments were chosen](#2-how-the-increments-were-chosen)
3. [Comparisons against constants](#3-comparisons-against-constants)
4. [Unsigned `i64` comparisons](#4-unsigned-i64-comparisons)
5. [Go-style `i64` booleans](#5-go-style-i64-booleans)
6. [Reference null tests and `br_on_cast_fail`](#6-reference-null-tests-and-br_on_cast_fail)
7. [Refactors in `BranchCondition`](#7-refactors-in-branchcondition)
8. [Measurements](#8-measurements)
9. [Tests and validation](#9-tests-and-validation)
10. [Rejected or deferred](#10-rejected-or-deferred)
11. [Incidental findings](#11-incidental-findings)
12. [File map](#12-file-map)

## 1. Summary

| Increment | Rewrite | Removes per site |
| --- | --- | --- |
| Signed `i32` vs 0, 1, -1; `eq`/`ne` vs 0 | `x < 0` → `IFLT`; `x < 1` → `IFLE`; `x > -1` → `IFGE` | the constant push (1 byte) |
| Unsigned `i32` vs 0 or 1 | `x >u 0` → `IFNE`; `x <u 1` → `IFEQ` | constant, helper call, `IFNE` |
| Unsigned `i32` vs power-of-two bound | `x <u 256` → `x >>> 8; IFEQ`; `x <u 2^31` → `IFGE` | helper call; 3 bytes or more |
| Unsigned `i64` | `Long.compareUnsigned` + `IFLT`/`IFGT`/`IFLE`/`IFGE` | the 0/1 helper; size-neutral |
| `i64.eqz (i64.extend_i32_{u,s} b)` | absorbed like `i32.eqz b` | two helper calls, `LCONST_0; LCMP` |
| `ref.is_null` | `IFNULL`/`IFNONNULL`, or `ICONST_M1; IF_ICMP*` for `funcref` | helper call |
| `br_on_null`, `br_on_non_null` | `DUP; IFNONNULL`/`IFNULL` | 3 instructions, 2 branches, 2 frames |
| `br_on_cast_fail` | branch on the cast result directly | 3 instructions, 2 branches |

Across five real-world modules, upstream main → this PR shrinks the compiled functions' total
bytecode by 2.0–4.0 % and removes 70–88 % of the Endive comparison helper calls; the unsigned
`i64` ones become calls to the JDK's `Long.compareUnsigned` (§8).

## 2. How the increments were chosen

Before changing anything, a throwaway census ran `WasmAnalyzer` over every function of five
real modules from `wasm-corpus` (.NET `basic.dotnet.wasm`, Go `main.go.wasm`, Rust
`greet-wasi.rs.wasm`, QuickJS `quickjs-provider.javy-dynamic.wasm`, `wasm/.../code.wasm`) and
counted, for every `IFEQ`/`IFNE`/`SELECT` the original pass did **not** fuse, the instruction
producing its condition. Relevant findings on the fork branch head (`82f39301`):

- **Unsigned `i32` comparisons were by far the largest unfused producer.** .NET: 3,045
  consumers (`lt_u` 1,154, `gt_u` 944, `ge_u` 487, `le_u` 300); QuickJS: 4,301.
- **Many of them compare against a constant**, frequently a power of two or its predecessor
  (`x <u 2`, `x >u 1`, `x >u 255`, `x <u 256`, `x <u 65536`, …): byte-range and small-enum
  checks.
- **Signed comparisons against zero** were already fused, but still pushed the 0 (.NET 220,
  QuickJS 751 sites).
- **Unsigned `i64` comparisons**: .NET 92, Go 574, QuickJS 959 consumers.
- **Go negates its `i64`-typed booleans as `i64.eqz (i64.extend_i32_u b)`**: 1,261 fused jumps
  in `main.go.wasm` still carried both helpers.
- Shapes considered and **dropped because they never occur** in these modules: a conditional
  jump over an unconditional one (`COND_JUMP L1; GOTO L2; L1:`), a `GOTO` to the next
  instruction, and a value-context `i32.eqz` applied to a comparison. All counts were 0, so
  none of them was implemented.

The census code is not part of the change. `FusionDumpTest.java` in the evidence directory
(§8) is the measuring harness that was kept.

## 3. Comparisons against constants

`takeCondition` (new, `WasmAnalyzer`) looks at the instruction before the comparison. If it is
an `I32_CONST`, it is the comparison's right operand, because it pushes exactly the value the
comparison pops last. Two rewrites are then tried before the plain comparison.

### 3.1 Zero tests (`i32ZeroTest`)

| comparison | becomes |
| --- | --- |
| `eq 0`, `le_u 0`, `lt_u 1` | `I32_EQZ` (`IFEQ`) |
| `ne 0`, `gt_u 0`, `ge_u 1` | `I32_NEZ` (`IFNE`) |
| `lt_s 0`, `le_s -1` | `I32_LTZ` (`IFLT`) |
| `gt_s 0`, `ge_s 1` | `I32_GTZ` (`IFGT`) |
| `le_s 0`, `lt_s 1` | `I32_LEZ` (`IFLE`) |
| `ge_s 0`, `gt_s -1` | `I32_GEZ` (`IFGE`) |

The constant is removed and the one-operand jump tests the variable operand. In the existing
approval snapshot `verifyExceptions` this is the only change:

```diff
-    ICONST_0
-    IF_ICMPEQ L0
+    IFEQ L0
```

### 3.2 Power-of-two bounds (`i32UnsignedShiftTest`)

`x <u 2^k` holds exactly when no bit at or above `k` is set, that is when `x >>> k == 0`. Its
complement `x >=u 2^k` is `x >>> k != 0`. `x <=u 2^k - 1` and `x >u 2^k - 1` are the same
tests. The comparison and its constant are replaced in the instruction list by
`I32_CONST k; I32_SHR_U` (both existing opcodes; `I32_SHR_U` emits a plain `IUSHR`), and the
condition becomes `I32_EQZ`/`I32_NEZ`. Two edge cases:

- `k == 0` (bound 1) needs no shift. This is the `lt_u 1` / `gt_u 0` row of §3.1.
- `k == 31` (bound `0x80000000`) is a sign test. `x <u 2^31` is `x >= 0`, so it becomes
  `I32_GEZ`/`I32_LTZ` without a shift.

Bound 0 (`x <u 0`, `x >=u 0`, `x >u -1`, `x <=u -1`) is a constant condition. It is rare and
left alone.

```
i32_lt_u_c256_br_if                       before                            after
                                          ILOAD 0                           ILOAD 0
                                          SIPUSH 256                        BIPUSH 8
                                          INVOKESTATIC Shaded.i32_lt_u      IUSHR
                                          IFNE L0                           IFEQ L0

i32_lt_u_cmin_br_if  (x <u 0x80000000)    ILOAD 0                           ILOAD 0
                                          LDC -2147483648                   IFGE L0
                                          INVOKESTATIC Shaded.i32_lt_u
                                          IFNE L0
```

### 3.3 Why only these constants: the C2 `fold_compares` bug

[branch-condition-fusion-jit-audit.md](branch-condition-fusion-jit-audit.md) explains why
unsigned `i32` comparisons are hazardous. On JDK 11–17, C2 can miscompile signed comparisons on
operands biased by `MIN_VALUE` (JDK-8376400). That is what `Integer.compareUnsigned`
becomes, and what a hand-written `x ^ MIN_VALUE` bias would produce. `MemCopyWorkaround`
keeps `I32_GE_U`/`I32_LT_U` behind a megamorphic call for exactly this reason.

None of the rewrites above introduces a bias. They produce either a single signed compare
against zero (`IFLT`/`IFGE`, the shape of every `x < 0` in ordinary Java) or an unsigned shift
followed by a zero test. Neither is the biased-operand pattern, and neither has the
Phi-of-constants that split-if needs. A general unsigned constant (`x <u 10`) or two variable
operands would need the bias, so they keep the helper call (and the workaround, where it
applies). The `ofComparison` javadoc now states this as the reason. The old reason, "fusing
saves nothing", was wrong, as the audit pointed out.

## 4. Unsigned `i64` comparisons

`I64_LT_U`, `I64_GT_U`, `I64_LE_U` and `I64_GE_U` are now `BranchCondition`s with the new
`LCMP_UNSIGNED` prefix. It emits `INVOKESTATIC java/lang/Long.compareUnsigned (JJ)I`, and the
jump tests the sign of the result:

```diff
     LLOAD 0
     LLOAD 2
-    INVOKESTATIC run/endive/runtime/OpcodeImpl.I64_LT_U (JJ)I
-    IFNE L0
+    INVOKESTATIC java/lang/Long.compareUnsigned (JJ)I
+    IFLT L0
```

This is still a call, and the bytecode size is unchanged. What it removes is the
`? TRUE : FALSE` materialization inside the helper and one call level: the helper itself
called `Long.compareUnsigned`. When the method is too large to be JIT-compiled, or C2 runs
out of inlining budget, the interpreter now makes one call instead of two.

**Why this is safe where the `i32` version is not:** C2's `IfNode::fold_compares` only merges
`int` compares (`cmpi_folds` requires `CmpI`). `Long.compareUnsigned` produces `CmpL`, so
JDK-8376400 cannot apply. That bug's reproducer and Endive's failures were all `int`.

## 5. Go-style `i64` booleans

Go's wasm backend keeps booleans in `i64` and negates them as
`(i64.eqz (i64.extend_i32_u b))`. Both instructions are helper calls in Endive
(`I64_EXTEND_I32_U` is `.shared`). Since zero- and sign-extension both preserve zero,
`i64.eqz (i64.extend_i32_{u,s} b)` is `i32.eqz b`. The absorption loop in
`fuseBranchConditions` now treats the pair like an `i32.eqz`: it removes both and flips the
jump. The usual case `i64.eqz (i64.extend_i32_u (i64.eqz x))` then fuses all the way down to
a single `LCONST_0; LCMP; IFNE`:

```diff
     LLOAD 0
-    INVOKESTATIC run/endive/runtime/OpcodeImpl.I64_EQZ (J)I
-    INVOKESTATIC run/endive/runtime/OpcodeImpl.I64_EXTEND_I32_U (I)J
     LCONST_0
     LCMP
-    IFEQ L0
+    IFNE L0
```

In `main.go.wasm` this removes 1,279 `I64_EXTEND_I32_U` and as many `I64_EQZ` helper calls.

## 6. Reference null tests and `br_on_cast_fail`

This implements Phase 1 of [ref-condition-fusion-plan.md](ref-condition-fusion-plan.md). The
design is simpler than the plan proposed.

### 6.1 `ref.is_null`

`takeCondition` recognizes a `REF_IS_NULL <valtype>` producer. The reference's JVM
representation decides the condition. `ValType.isObjectRef(id, typeSection)` (the same
predicate the existing `REF_IS_NULL` emitter uses) selects `REF_IS_NULL` (`IFNULL`) for object
references (GC refs, `externref`), and `INT_REF_IS_NULL` (prefix `REF_NULL_VALUE`, then
`IF_ICMPEQ`) for `funcref`, which is an `int`. `fuseBranchConditions` therefore takes the type
section as a parameter. Since these are ordinary `BranchCondition`s, `select` on a null test
(`SELECT_COND`) and loop back edges work without extra code. The plan had deferred both.

```diff
     INVOKESTATIC .../CompiledMachineFuncGroup_0.func_1 (...)Ljava/lang/Object;
-    INVOKESTATIC .../CompiledMachineShaded.isGcRefNull (Ljava/lang/Object;)Z
-    IFEQ L0
+    IFNONNULL L0
```

### 6.2 `br_on_null` / `br_on_non_null`

Previously lowered to `BR_ON_NULL_CHECK` (`DUP`, then a branch to materialize 0/1) followed by
`IFEQ` on that 0/1. The plan proposed a dedicated `REF_NULL_JUMP` opcode with a "dup" flag.
Instead, the analyzer now emits a new generic `DUP <valtype>` opcode followed by the existing
`REF_IS_NULL` and `IFEQ` (or `IFNE` for `br_on_non_null`). The ordinary fusion turns
`REF_IS_NULL; IFEQ` into one null jump, so no special emitter is needed:

```
struct_br_on_null    before                         after
                     DUP                            DUP
                     IFNULL L0                      IFNONNULL L0
                     ICONST_0                       POP
                     GOTO L1                        GOTO L1
                    L0:                            L0:
                     ICONST_1                       ...
                    L1:
                     IFEQ L2
                     POP
                     GOTO L3
                    L2:
                     ...
```

`BR_ON_NULL_CHECK` and `BR_ON_NON_NULL_CHECK` and their emitters are removed. The plan
suggested keeping them in case fusion ever fails to fire. That cannot happen here: the
analyzer emits the producer and consumer back to back. If the pass were disabled,
`DUP; REF_IS_NULL; IFEQ` would still be correct.

### 6.3 `br_on_cast_fail`

`BR_ON_CAST_FAIL_CHECK` computed the cast result and then inverted it with two more branches,
so that a following `IFEQ` could branch on "matched". The analyzer now emits the plain
`BR_ON_CAST_CHECK` followed by `IFNE` on the match label, and `BR_ON_CAST_FAIL_CHECK` is
removed. The cast helper call itself is unchanged.

## 7. Refactors in `BranchCondition`

- **Prefixes are a private `Prefix` enum** (`NONE`, `LCMP_ZERO`, `LCMP`, `LCMP_UNSIGNED`,
  `FCMPL`, `FCMPG`, `DCMPL`, `DCMPG`, `REF_NULL_VALUE`). They used to be overloaded
  `Opcodes` constants (`NOP`, `LCONST_0`, …). `LCMP_UNSIGNED` and `REF_NULL_VALUE` have no
  natural opcode tag, and the enum makes `emitJump`'s switch exhaustive.
- **`negate`** learns the `IFNULL`/`IFNONNULL` pair (198/199). It is package-private so a
  test can check every pair and `negate(negate(j)) == j` for each condition's jump. The
  existing base trick covered `IF_ACMPEQ`/`IF_ACMPNE` already, since they continue the
  `IF_ICMP*` run.
- New conditions: `I32_EQZ`, `I32_LTZ`, `I32_GTZ`, `I32_LEZ`, `I32_GEZ`, `I64_{LT,GT,LE,GE}_U`,
  `REF_IS_NULL`, `INT_REF_IS_NULL`. Ids remain enum ordinals that only live inside one
  compilation (`EnumOrdinal` suppression from `854e77ca`).

## 8. Measurements

Raw outputs and the harness are in
[data/branch-condition-fusion-2026-10-17/](data/branch-condition-fusion-2026-10-17/).

**Method.** `FusionDumpTest` compiles each module with `Compiler.builder(module).build()` and
writes the classes. `method_sizes.py` sums the `Code` attribute lengths of all `func_*`
methods (using the existing `parse_class_methods.py`) and counts those over 8,000 bytes. That
is HotSpot's `HugeMethodLimit`: with the default `DontCompileHugeMethods` such methods are
never JIT-compiled. `helper_calls.sh` counts `invokestatic`s of comparison and null-test
helpers with `javap`. These are static counts, not executed counts, and say nothing about
time.

**Baselines.** Upstream main `1580f534` → PR commit 1 `e4105edf` (the original fusion) → PR
commit 2 `3ba75b28` (this change), all built from `pr/fuse-branch-conditions`.

| module | upstream main | + original fusion | + this change | total | methods > 8000 B |
| --- | ---: | ---: | ---: | ---: | --- |
| `basic.dotnet.wasm` | 1,569,334 | 1,514,110 | 1,511,973 | −3.66 % | 15 → 14 → 14 |
| `quickjs-provider.javy-dynamic.wasm` | 1,076,795 | 1,036,979 | 1,033,998 | −3.97 % | 13 → 10 → 10 |
| `javy-demo.js.javy.wasm` | 1,008,574 | 971,448 | 968,790 | −3.94 % | 12 → 11 → 11 |
| `main.go.wasm` | 1,706,688 | 1,679,624 | 1,671,946 | −2.04 % | 13 → 12 → 12 |
| `greet-wasi.rs.wasm` | 78,820 | 76,817 | 76,554 | −2.87 % | 0 |
| `code.wasm` | 23,338 | 22,789 | 22,684 | −2.80 % | 0 |
| `ref_conditions.wat.wasm` (fixture) | 2,060 | 2,027 | 1,704 | −17.28 % | 0 |

Comparison/null-test helper `invokestatic`s. `Long.compareUnsigned`, a JDK method, is
excluded and listed separately:

| module | upstream main | + original fusion | + this change | `Long.compareUnsigned` |
| --- | ---: | ---: | ---: | ---: |
| `basic.dotnet.wasm` | 22,498 | 3,950 | 3,374 | 63 |
| `quickjs-provider.javy-dynamic.wasm` | 20,100 | 6,217 | 4,668 | 959 |
| `javy-demo.js.javy.wasm` | 19,057 | 6,095 | 4,676 | 914 |
| `main.go.wasm` | 18,662 | 5,423 | 2,327 | 1,815 |
| `greet-wasi.rs.wasm` | 1,088 | 413 | 329 | 1 |

Reading the numbers:

- The original fusion accounts for most of the size reduction. This change adds 0.1–0.5 % on
  the real modules. Its main effect is on helper calls: .NET −15 %, Javy −23 %, QuickJS −25 %, Go −57 % relative to
  the original fusion. On GC code the size effect is large (−16 % on the fixture). No
  real-world GC module was available to measure.
- No method grew, except one 3-byte method that became 4 bytes. Its `LDC` turned into
  `LDC_W` because the class's constant pool passed 256 entries (`Long.compareUnsigned` adds
  entries). This is the only way the change can increase a method's size, and it is bounded
  by 1 byte per `LDC` in classes near that threshold.
- The remaining helpers are dominated by unsigned `i32` comparisons with variable or
  non-power-of-two operands (.NET 2,481, QuickJS 4,003, Go `I32_LE_U` 1,078). §3.3 explains
  why they are left alone. QuickJS's most frequent one is `x <u -11` (1,339 sites, a NaN-boxing
  tag check).

**Run time** was measured afterwards, locally, in
[branch-condition-fusion-benchmarks.md](branch-condition-fusion-benchmarks.md). Comparing
upstream main with the whole PR on identical modules, steady state is 4–10 % faster wherever
the hot code is compiled: Lua −5.5 %, Lua split −4.1 %, SQLite split −9.5 %, QuickJS split
−6.4 %. Runs whose hot function falls back to the interpreter are neutral, and Endive's JMH
suite shows no effect. The numbers come from one laptop session, so they are exploratory: the
thesis evidence policy requires a fixed-runner repeat before citing a speedup.

## 9. Tests and validation

New and changed tests (`compiler` module):

- `BranchConditionTest.constantOperandsMatchInterpreter`: differential test for all exports of
  the new fixture `branch_conditions_const.wat`. That is 10 `i32` comparisons × 12 constants
  (0, 1, −1, −2, 2, 7, 8, 255, 256, 2³⁰, `MAX_VALUE`, `MIN_VALUE`) × 4 shapes (`br_if`,
  `if` + `eqz`, `select`, loop back edge) = 480 functions, each run on 22 operands placed at
  and around every boundary. Interpreter and compiler must agree.
- `BranchConditionTest.rewritesComparisonsAgainstConstants`: structural test. For every export,
  an independent oracle (`constForm`, written separately from `WasmAnalyzer`) predicts zero
  test / shift test / plain comparison / helper. The test asserts the produced conditions,
  that the shift appears exactly when predicted, and that helpers remain exactly when
  predicted.
- `BranchConditionTest.negatedJumpsAreComplements`: every jump pair, including
  `IFNULL`/`IFNONNULL`, and `negate` being an involution for every condition.
- `BranchConditionTest.fusesComparisons` (renamed from `fusesSignedAndFloatComparisons`):
  unsigned `i64` rows now expect fusion. New `i64_extend_{u,s}_{lt_s,eqz}` rows in
  `branch_conditions.wat` assert that the extends are gone.
- `RefConditionTest` (new): differential and structural tests over `ref_conditions.wat` (new).
  It covers null tests on `struct`, `funcref` (int-represented), `externref` and `anyref`
  references under `br_if`, `br_if` + `eqz`, `if`, `if` + `eqz`, `select`, wide `select`,
  value use, loop back edge and unwinding `br_if`. It also covers `br_on_null`
  (plain, fall-through value, unwind, loop back edge), `br_on_non_null` (plain, unwind),
  `select` of references on a null test, `br_on_cast`, and `br_on_cast_fail` (to `(ref $s)`,
  `(ref null $s)`, `(ref i31)`, and as a loop back edge with a loop parameter).
- `ApprovalTest.verifyRefConditions` (new snapshot), and `verifyExceptions` updated (§3.1).

**Mutation check.** Three deliberate bugs were introduced one at a time and each made the
differential test fail: swapping the `k == 31` sign test, skipping the shift for `k == 1`,
and mapping `le_u 0` to `NEZ`. Example failure:
`i32_lt_u_cmin_br_if(0) differs ==> expected: <0> but was: <1>`.

**Runs** (all passing):

| what | JDK | result |
| --- | --- | --- |
| `compiler` (with Error Prone `-Werror`, Checkstyle, Spotless) | 21.0.12.1 | 47 tests, classpath and module path |
| `compiler-tests`: spec testsuite through the compiler, including `SpecV1Gc*` (`BrOnNull`, `BrOnNonNull`, `BrOnCast`, `BrOnCastFail`, `RefIsNull`, `I31`, …) | 21.0.12.1 | 34,120 tests, 63 skipped (pre-existing) |
| `machine-tests` | 21.0.12.1 | 247 |
| `compiler`, `wabt` (wat2wasm compiled by Endive), `machine-tests` | Temurin 17.0.20.1 | all pass (47 + 6 + 247) |
| PR branch on upstream main: `compiler`, `compiler-tests`, `machine-tests`, `wabt` | 21.0.12.1 | all pass (`compiler` incl. Error Prone/Checkstyle; 34,120 spec; 247; 6) |

JDK 17 is the range where the C2 bug is live. Whether 17.0.20 already carries the upstream fix
was not checked. The JDK 17 run shows that nothing regressed, not that the rewrites are immune
to the bug.

## 10. Rejected or deferred

- **General unsigned `i32` fusion** (variable operands, arbitrary constants): this is the
  largest remaining gap (§8), but it needs the `MIN_VALUE` bias that triggers JDK-8376400 on
  JDK ≤ 17. It could be enabled when the *executing* JVM is ≥ 18 (or contains the fix). That
  is knowable for `MachineFactoryCompiler` at run time, but not for the build-time compiler,
  whose output runs elsewhere. It needs an explicit option and maintainer agreement.
- **`ref.eq` fusion** (Phase 2 of the plan): `Shaded.refEq` compares `i31ref`s by value, so
  `IF_ACMPEQ` is only valid when an operand's static type excludes `i31`. The analyzer's type
  stack is not a sound basis for that yet: `SELECT`/`SELECT_T` type their result as the
  *second* operand's type (`stack.peek()` after popping the condition) instead of the
  annotated result type. A `select_t (result eqref)` over an `i31ref` and a `(ref $s)` would
  be typed `(ref $s)`, so an i31 gate based on it would be wrong. That would have to be fixed
  first.
- **`ref.test` / `br_on_cast` result tests**: the cast check genuinely needs its helper, and
  its 0/1 result already feeds a single `IFEQ`/`IFNE`. Nothing is left to fuse.
- **Jump threading, value-context comparison inversion**: 0 occurrences in the census (§2).
- **`I64_EXTEND_I32_U` as a helper call**: not a branch-condition issue, but the census shows
  16,615 such calls in `main.go.wasm` alone. Making it an intrinsic
  (`I2L; LDC 0xFFFFFFFFL; LAND`, or `Integer.toUnsignedLong`) would be a separate, simple
  PR.

## 11. Incidental findings

- **The "function 4725 exceeds the method-size limit" failure in `wasm-tools`**, recorded in
  [FORK_CHANGES.md](../../FORK_CHANGES.md) §9 and the split-function results, is **not**
  caused by the compiler. `wasm-tools/target/wasm-tools.wasm` was an Oct-2025 download that the
  build never refreshes once the file exists. Both branches pin wasm-tools 1.256.0. With a
  fresh download, the fork branch, the PR branch and upstream main all compile `wasm-tools`.
  With the stale file, upstream main fails at function 4725 too. The stale file is kept as
  `wasm-tools/target/wasm-tools.wasm.stale-2025`.
- A locally built `redline/cranelift_bridge.wasm` (19 Sep) shadows the pinned bridge and breaks
  Redline's compilation against current upstream main, which is the sharp edge CONTRIBUTING
  describes. Move it aside when building upstream-based branches.
- `compiler/src/test/resources/run/endive/approvals/ApprovalTest.verifyLotsOfArgs.approved.txt`
  is gitignored and generated on first run. The copy in this working tree was generated with
  the fork's ABI changes (`b4d973e1`), so `verifyLotsOfArgs` fails on an upstream-based branch
  until that file is deleted.

## 12. File map

| File | Change |
| --- | --- |
| `compiler/.../BranchCondition.java` | `Prefix` enum; zero-test, unsigned-`i64` and null-test conditions; `negate` handles `IFNULL`; javadoc gives the JIT-bug rationale |
| `compiler/.../WasmAnalyzer.java` | `fuseBranchConditions(in, typeSection)`; `takeCondition`, `i32ZeroTest`, `i32UnsignedShiftTest`, `isExtendedEqz`; `br_on_null`/`br_on_non_null`/`br_on_cast_fail` lowering |
| `compiler/.../CompilerOpCode.java` | `+DUP`; `−BR_ON_NULL_CHECK`, `−BR_ON_NON_NULL_CHECK`, `−BR_ON_CAST_FAIL_CHECK` |
| `compiler/.../Emitters.java`, `EmitterMap.java` | `DUP` emitter; three check emitters removed |
| `compiler/src/test/.../BranchConditionTest.java` | three new tests, updated expectations |
| `compiler/src/test/.../RefConditionTest.java` | new |
| `compiler/src/test/.../ApprovalTest.java` + snapshots | `verifyRefConditions` new, `verifyExceptions` updated |
| `wasm-corpus/.../wat/branch_conditions_const.wat` (+ `.wasm`) | new, 480 functions (generated, wabt 1.0.40 `wat2wasm`) |
| `wasm-corpus/.../wat/ref_conditions.wat` (+ `.wasm`) | new, 67 exported functions (Endive `wasm-tools` `Wat2Wasm`; wabt lacks GC) |
| `wasm-corpus/.../wat/branch_conditions.wat` (+ `.wasm`) | 16 `i64_extend_*` functions appended |
