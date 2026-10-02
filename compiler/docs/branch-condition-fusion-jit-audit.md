# Audit: branch-condition fusion vs. the C2 `fold_compares` bug

Audited change: commit `f5225324` ("Fuse comparisons into the branches and selects that consume
them"), described in [branch-condition-fusion.md](branch-condition-fusion.md).

Question: does the fusion interact with the JVM JIT bug workaround described in
[Finding a JVM JIT Bug the Hard Way](https://endive.run/blog/finding-a-jvm-jit-bug-the-hard-way/),
and was the old helper-call lowering of comparisons a consequence of that bug?

## Summary

- **No conflict with the workaround.** Fusion never touches the two protected opcodes
  (`I32_GE_U`, `I32_LT_U`); their workaround call is still emitted, including when an `i32.eqz`
  in between is absorbed.
- **Fusion probably reduces exposure to the bug.** It removes the "0/1 value, then `if`"
  shape that C2's split-if targets, which is one of the bug's three ingredients. It does not prove
  immunity.
- **The old lowering was not caused by the bug.** Comparisons have been helper calls since the
  first AOT compiler (May 2024), nine months before the bug was reported. It was a uniform design
  simplification, not a workaround.
- **It was not free, though.** In methods near the 8000-byte limit, the helper calls are not
  inlined, so the old lowering cost performance, not just bytecode size.
- **Follow-up hazard.** The unsigned-fusion idea in
  [branch-condition-fusion.md §9](branch-condition-fusion.md#9-limits-and-follow-ups), biasing the
  operands by `MIN_VALUE` and using a signed jump, reproduces the bug's triggering pattern and
  would bypass the workaround on JDK 11–17.
- **Two unrelated problems found:** the commit does not build under the project's Error Prone
  `-Werror` setting, and forcing the workaround on JDK 18+ throws a `NullPointerException`
  (a pre-existing bug).

## 1. The bug and the workaround

The bug (JDK 11–17, C2 only; fixed upstream in
[openjdk/jdk#30677](https://github.com/openjdk/jdk/pull/30677), integrated 2026-04-30) needs three
C2 optimizations to interact:

1. Signed integer comparisons with an uncommon trap on a never-taken branch.
2. **Split-if** (`do_split_if()`), which pushes an `if` through a Phi of constants and modifies the
   uncommon trap's saved state.
3. **If-folding** (`IfNode::fold_compares()`), which merges two signed comparisons into one
   unsigned comparison without knowing that split-if already changed the trap state.
   Deoptimization then resumes at the wrong bytecode with corrupted state.

In Endive, the trigger was `OpcodeImpl.I32_GE_U`:

```java
public static int I32_GE_U(int a, int b) {
    return Integer.compareUnsigned(a, b) >= 0 ? TRUE : FALSE;
}
```

`Integer.compareUnsigned` becomes `compare(a + MIN_VALUE, b + MIN_VALUE)`, which gives exactly the
signed comparisons `fold_compares` wants. The `? TRUE : FALSE` result, tested again by the caller,
is the Phi-of-constants that split-if goes after. The upstream reduced test has the same shape:
`v = 0/1; if (v == 0) throw; if (...)`.

On JDK 18+ the bug is masked by an unrelated change,
[JDK-8276162](https://github.com/openjdk/jdk/pull/6101), which lowers the `compareUnsigned`
pattern to an unsigned comparison before the if-folding stage runs.

The workaround stops C2 from inlining the affected helpers. `MemCopyWorkaround` trains a
functional-interface call site with several lambdas during static initialization, so C2 sees
it as megamorphic. It covers:

| Commit | Date | Covers |
|---|---|---|
| `867b1e2c` (chicory#844) | 2025-04-21 | `memoryCopy` |
| `b2279d1c` (chicory#1178) | 2026-01-28 | `I32_GE_U` |
| `13f3c79c` (#134) | 2026-07-29 | `I32_LT_U` |

It is active when `java.version` ≤ 17, or when forced with `-Dendive.memCopyWorkaround`. The
compiler routes the two opcodes through `Shaded.i32_ge_u` / `Shaded.i32_lt_u` (`EmitterMap`
`.intrinsic(...)` entries) instead of the usual `.shared(..., OpcodeImpl.class)`.

## 2. How the fusion interacts with the workaround

### 2.1 Protected opcodes are untouched

`BranchCondition.ofComparison` returns empty for every unsigned comparison, so `I32_GE_U` and
`I32_LT_U` are never removed from the instruction list.

When an `i32.eqz` sits between an unsigned comparison and its consumer, the fusion loop in
`WasmAnalyzer.fuseBranchConditions` removes the `eqz`, reaches `I32_GE_U`, and stops. The result is:

```
invokestatic Shaded.i32_ge_u   // workaround path preserved
ifeq target                    // eqz absorbed by inverting the jump
```

That is semantically equivalent (the helper returns exactly 0 or 1) and keeps the workaround call.

### 2.2 Fused sites lose a bug ingredient

Before fusion, a signed comparison feeding a branch looked like this after C2 inlining:

```
CmpI -> If -> Phi(1, 0) -> CmpI(phi, 0) -> If
```

This is the canonical split-if input and the same shape as the upstream reproducer. After fusion,
the comparison feeds the jump directly (`if_icmp<cond>`, or `lcmp`/`fcmp<op>` + `if<cond>`), so
there is no Phi of constants at that site.

`fold_compares` may still fold two directly-branching signed comparisons on JDK ≤ 17. Without
split-if having modified the trap, that fold is the correct, intended optimization. Split-if can
still act on other Phis in the method, so this is **a reduction in exposure, not a proof of
immunity**.

The signed helpers (`I32_LT_S` etc.) had this Phi shape and were inlined on JDK 17 throughout, and
were never implicated in the bug. The unsigned `+ MIN_VALUE` comparisons were what made the fold
possible.

### 2.3 Hazard: fusing unsigned comparisons with a bias

[branch-condition-fusion.md §9](branch-condition-fusion.md#9-limits-and-follow-ups) proposes fusing
unsigned comparisons by biasing both operands (`x ^ MIN_VALUE`) and using the signed jump. That is
the exact pattern from the blog post: signed comparisons on `MIN_VALUE`-biased operands, which
`fold_compares` turns into unsigned ones. It would also skip `MemCopyWorkaround` entirely, because
there is no call left to make megamorphic.

Recommendation:

- Do not fuse unsigned `i32` comparisons unconditionally. If it is ever done, gate it on
  JDK ≥ 18 (or on a JDK that contains the upstream fix), for example with the same
  `shouldUseMemWorkaround()` check. Validate it against the large wat2wasm regression on
  Temurin 17.
- Fix the rationale in the `ofComparison` javadoc and in §3/§9 of the fusion doc. The current
  text says unsigned fusion "saves nothing" because it "would still need a helper call". That is
  not strictly true, since the bias needs no call. The real constraint is this JIT bug.

`i64` unsigned comparisons (`Long.compareUnsigned`) are not part of the documented trigger, which
was an `int` pattern. They are excluded from fusion today for the same reason as the `i32` ones.

### 2.4 Unprotected unsigned opcodes (pre-existing)

`I32_GT_U` and `I32_LE_U` use the same `Integer.compareUnsigned` pattern as the two protected
opcodes, but are still `.shared(..., OpcodeImpl.class)`, so they are inlined normally on JDK ≤ 17.
This commit does not change that. It is a gap in the workaround's coverage, not something the
fusion introduced.

## 3. Was the old lowering caused by the bug?

No.

- Comparisons were registered as `.shared(I32_GE_S, OpcodeImpl.class)` etc. in the very first
  AOT compiler commit, `e31a1aac` ("Experimental AOT Compiler", 2024-05-13). That commit describes
  the approach as "shared static opcode implementations, and auto-intrinsification": nearly every
  numeric opcode is a static helper call that C2 is expected to inline.
- The bug was reported in February 2025
  ([chicory#755](https://github.com/dylibso/chicory/issues/755)).
- None of the workaround commits changed how signed or float comparisons, or branches, were
  lowered. They only rerouted `memoryCopy`, `I32_GE_U` and `I32_LT_U`.
- Before `f5225324`, no `IF_ICMP*`, `LCMP` or `FCMP*` was ever emitted for a Wasm comparison.
  (`git log -S` hits for `IF_ICMP` before that are in approval-test fixtures for unrelated bounds
  checks.) Nothing was ever changed to native jumps and then reverted.

So the helper lowering was a uniform design simplification: comparisons followed the same pattern
as every other opcode.

## 4. Why the old lowering was still costly: the 8000-byte limit

The simplification is harmless only if C2 actually inlines the helper, because inlining lets C2
fold the 0/1 value away. For large methods, it does not:

- **Over 8000 bytes (`HugeMethodLimit`):** with the default `-XX:+DontCompileHugeMethods`, the
  method is never JIT-compiled. It runs in the interpreter, and every comparison is a real call.
- **Just under 8000 bytes:** the method is compiled, but C2's inlining budget is
  `DesiredMethodLimit` (8000). It counts the root method's own bytecode plus everything inlined so
  far. With `ClipInlining` (the default), a callee that would push the total to 8000 or more is not
  inlined (`PrintInlining`: `size > DesiredMethodLimit`). C1 has a similar total-inlining cutoff.
  A 7900-byte method has about 100 bytes of budget left, which is about ten ~10-byte comparison
  helpers. After that, every comparison is a real call, keeps its 0/1 result and extra `ifne`, and
  hides the condition from C2's branch profiling and loop and range-check optimizations.

Each old-style comparison site therefore costs twice:

1. 3 bytes of the method's own size (`invokestatic`).
2. About 10 bytes of inlining budget when the helper is inlined.

A fused comparison costs 0–2 bytes of method size and no inlining budget, which leaves the budget
for helpers that cannot be fused (memory access, unsigned comparisons).

This is the same size cliff the function-splitting work targets, so it matters most for
exactly the large, generated methods Endive produces.

> **Evidence status:** the limits above are stated from HotSpot's source and flag defaults, not
> from a measurement in this audit. To support the claim, run a function just under 8000 bytes
> with `-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining` on a pinned JDK and count the
> `size > DesiredMethodLimit` lines for `OpcodeImpl.I32_*` before and after fusion.

## 5. Other findings

### 5.1 The commit does not build under the project settings

The root `pom.xml` sets `maven.compiler.failOnWarning=true` and runs Error Prone. Compiling
`compiler` fails with:

```
BranchCondition.java:[64,23] [EnumOrdinal] You should almost never invoke the Enum.ordinal()
method or depend on the enum values by index.
```

Fix: give `BranchCondition` an explicit `id` field and look it up by that instead of by
`ordinal()` / `values()[id]`, or suppress the check locally with a justification.

### 5.2 Forcing the workaround on JDK 18+ throws a `NullPointerException` (pre-existing)

`Shaded` reads `-Dendive.memCopyWorkaround`, but `MemCopyWorkaround`'s static initializer only
fills in its function fields when `shouldUseMemWorkaround()` (the Java version check) is true. On
JDK 18+ with the property set to `true`, the fields stay `null`:

```
NullPointer Cannot invoke "run.endive.runtime.MemCopyWorkaround$I32GEUFunc.apply(int, int)"
because "run.endive.runtime.MemCopyWorkaround.i32ltuFunc" is null
```

This affects `memoryCopy`, `i32_ge_u` and `i32_lt_u` alike, and predates the fusion. The new
`BranchConditionTest` just exposes it through its unsigned cases. Fix: have the static
initializer honour the same property as `Shaded`.

## 6. Test results

Run on OpenJDK 21.0.12.1, with `-Dmaven.compiler.failOnWarning=false` to get past §5.1:

| Run | Result |
|---|---|
| `BranchConditionTest`, workaround off | 2/2 pass |
| `BranchConditionTest`, `-Dendive.memCopyWorkaround=true` | `compiledMatchesInterpreter` fails with the NPE from §5.2 |

**Not tested:** JDK 17, the only range where the bug is live (no JDK 17 was available). Before
merging, run `BranchConditionTest` and the large wat2wasm regression test on Temurin 17.

## 7. Recommendations

1. Fix the Error Prone failure (§5.1).
2. Correct the unsigned-fusion rationale in the `ofComparison` javadoc and the fusion doc, and mark
   the bias idea in §9 as unsafe on JDK ≤ 17 (§2.3).
3. Run the tests on Temurin 17 (§6).
4. Measure the inlining effect near 8000 bytes before citing it in the thesis (§4).
5. Separately: fix the property override in `MemCopyWorkaround` (§5.2), and decide whether
   `I32_GT_U` / `I32_LE_U` need the workaround too (§2.4).
