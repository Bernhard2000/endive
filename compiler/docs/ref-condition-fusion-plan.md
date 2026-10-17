# Implementation plan: fusing reference conditions into branches

> **Status (2026-10-17):** Phase 1 is implemented in `5a02a7dd`, differently from §2. There is
> no `REF_NULL_JUMP` opcode. `ref.is_null` became two ordinary `BranchCondition`s
> (`REF_IS_NULL` → `IFNULL`, `INT_REF_IS_NULL` → `ICONST_M1; IF_ICMPEQ`), and `br_on_null` /
> `br_on_non_null` are lowered to a new generic `DUP` + `REF_IS_NULL` + `IFEQ`/`IFNE`, which
> the normal pass fuses. `select` on a null test is covered as well, and
> `BR_ON_NULL_CHECK` / `BR_ON_NON_NULL_CHECK` are removed. `br_on_cast_fail` also lost its
> 0/1 inversion. Phase 2 (`ref.eq`) is **not** implemented: besides the i31 constraint in
> §3.1, the analyzer types `select`/`select_t` results by their second operand rather than
> the annotated type, so static types cannot yet prove an operand non-i31. Details in
> [branch-condition-fusion-extensions.md §6 and §10](branch-condition-fusion-extensions.md).

Follow-up to [branch-condition-fusion.md](branch-condition-fusion.md), which fused numeric
comparisons into the `if` / `br_if` / `select` consuming them. Two further condition families
still materialize a 0/1 `i32` that is then immediately tested:

- **Phase 1 — null checks** (`ref.is_null`, `br_on_null`, `br_on_non_null`). Highest payoff, no
  correctness hazard, and `br_on_null` currently *branches in order to produce a boolean that is
  then branched on*.
- **Phase 2 — `ref.eq`.** Smaller and gated by a correctness constraint: `Shaded.refEq` is not
  plain identity.

The two phases are independent; Phase 1 can ship alone.

## 1. Current state

### 1.1 `br_on_null` / `br_on_non_null`

`WasmAnalyzer` lowers `br_on_null` to a check instruction plus `IFEQ` (`WasmAnalyzer.java:540`):

```java
// BR_ON_NULL_CHECK: DUPs ref, pushes 1 if null, 0 if not null
// JVM stack after check: [ref, 0_or_1]
result.add(new CompilerInstruction(CompilerOpCode.BR_ON_NULL_CHECK, ref.id()));

// IFEQ: pops boolean; if 0 (not null) -> jump to notNullLabel
var notNullLabel = nextLabel++;
result.add(new CompilerInstruction(CompilerOpCode.IFEQ, notNullLabel));

// null path: drop the DUP'd ref, then branch
result.add(new CompilerInstruction(CompilerOpCode.DROP, ref.id()));
...
result.add(new CompilerInstruction(CompilerOpCode.GOTO, ins.labelTrue()));
result.add(new CompilerInstruction(CompilerOpCode.LABEL, notNullLabel));
```

and `BR_ON_NULL_CHECK` emits (`Emitters.java:2182`):

```java
asm.dup();
var isNull = new Label();
var end = new Label();
if (isGcRef) {
    asm.ifnull(isNull);
} else {
    asm.iconst(REF_NULL_VALUE);
    asm.ificmpeq(isNull);
}
asm.iconst(0); // not null
asm.goTo(end);
asm.mark(isNull);
asm.iconst(1); // is null
asm.mark(end);
```

So the emitted bytecode is:

```
DUP
IFNULL L_isNull
ICONST_0
GOTO L_end
L_isNull:
ICONST_1
L_end:
IFEQ L_notNull        ; test the constant we just branched to produce
```

Seven instructions, three labels, two branches — to express what `DUP; IFNONNULL L_notNull` says
in two instructions. `br_on_non_null` has the mirror form (`BR_ON_NON_NULL_CHECK`,
`WasmAnalyzer.java:567`).

### 1.2 `ref.is_null`

`WasmAnalyzer.java:1320` emits `REF_IS_NULL <valtype id>`, which is a call
(`Emitters.java:476`):

```java
var type = valType(ins.operand(0), ctx);
if (type.isObjectRef()) {
    emitInvokeStatic(asm, ShadedRefs.GC_REF_IS_NULL);   // Shaded.isGcRefNull(Object) -> ref == null
} else {
    emitInvokeStatic(asm, ShadedRefs.REF_IS_NULL);      // Shaded.isRefNull(int) -> ref == REF_NULL_VALUE
}
```

Both helpers are one-liners (`Shaded.java:165`, `:169`). When the result feeds an `if` or `br_if`,
the call plus the `IFEQ`/`IFNE` should be one `IFNULL`/`IFNONNULL` (or `ICONST; IF_ICMPEQ` for
int-encoded refs).

### 1.3 `ref.eq`

`Emitters.REF_EQ` always calls `Shaded.refEq` (`Shaded.java:1465`):

```java
public static int refEq(Object a, Object b) {
    if (a == b) return 1;
    if (a == null || b == null) return 0;
    if (a instanceof WasmI31Ref && b instanceof WasmI31Ref) {
        return ((WasmI31Ref) a).value() == ((WasmI31Ref) b).value() ? 1 : 0;
    }
    return 0;
}
```

**This is not identity.** `WasmI31Ref` instances are compared by value, so `IF_ACMPEQ` would be
wrong whenever an operand can be an `i31ref`. Phase 2 has to gate on that.

## 2. Phase 1 — null-check fusion

### 2.1 New compiler opcode

```java
// CompilerOpCode.java
// fused null test + conditional jump: [label, ValType id, dup ref first (0/1), jump when null (0/1)]
REF_NULL_JUMP,
```

Separate from `COND_JUMP` rather than a new `BranchCondition` entry, because:

- the test depends on the *runtime representation* of the ref (`isObjectRef` → `IFNULL`,
  int-encoded → `ICONST REF_NULL_VALUE; IF_ICMPEQ`), which is resolved from a `ValType` id against
  the emitter's `Context`, not known to the pass;
- `br_on_null` needs the `dup` and `ref.is_null` does not, so a flag has to ride along anyway.

`CompilerInstruction.labelTargets()` gets the same treatment as `COND_JUMP` — only operand 0 is a
label:

```java
case COND_JUMP:
case REF_NULL_JUMP:
    return new long[] {operands[0]};
```

### 2.2 Pass changes (`WasmAnalyzer.fuseBranchConditions`)

Today the look-back inspects only `opcode()`. It now needs the producing *instruction*, to read
its `ValType` operand:

```java
var prevIns = out.get(out.size() - 1);
```

Recognized producers, after the existing `I32_EQZ` absorption loop (which already gives
`i32.eqz (ref.is_null x)` for free):

| previous instruction | `dup` | pushes 1 when | fused into |
| --- | --- | --- | --- |
| `REF_IS_NULL <t>` | 0 | ref is null | `REF_NULL_JUMP [label, t, 0, jumpWhenNull]` |
| `BR_ON_NULL_CHECK <t>` | 1 | ref is null | `REF_NULL_JUMP [label, t, 1, jumpWhenNull]` |
| `BR_ON_NON_NULL_CHECK <t>` | 1 | ref is **non**-null | `REF_NULL_JUMP [label, t, 1, !jumpWhenNull]` |

with `jumpWhenNull` computed exactly like the existing `whenTrue`: `IFNE` (and `SELECT`) start at
`true`, `IFEQ` at `false`, each intervening `i32.eqz` flips it, and `BR_ON_NON_NULL_CHECK` flips it
once more because its boolean is already inverted.

Suggested shape — keep the numeric and reference paths in one loop so `eqz` handling is shared:

```java
// after the I32_EQZ absorption loop, before the ofComparison() check
var refKind = RefConditionKind.of(prev);          // null | IS_NULL | ON_NULL | ON_NON_NULL
if (refKind != null) {
    out.remove(out.size() - 1);
    // emit REF_NULL_JUMP instead of COND_JUMP
}
```

`SELECT` deliberately stays out of scope in Phase 1: `select` with a `ref.is_null` condition is
rare, and supporting it means a `SELECT_REF_COND` twin of `SELECT_COND`. If the structural test
shows it in real modules, add it the same way.

### 2.3 Emitter (`Compiler.java`, main switch)

Mirrors `COND_JUMP`, including the back-edge `checkInterruption` handling — `br_if (ref.is_null …)`
to a loop header is legal, so the negated-jump-over-the-check pattern is required:

```java
case REF_NULL_JUMP:
    {
        var type = valType(ins.operand(1), ctx);
        boolean dup = ins.operand(2) != 0;
        boolean whenNull = ins.operand(3) != 0;
        if (visitedTargets.contains(ins.operand(0))) {
            Label skip = new Label();
            emitNullJump(asm, type, dup, !whenNull, skip);
            emitInvokeStatic(asm, CHECK_INTERRUPTION);
            asm.goTo(labels.get(ins.operand(0)));
            asm.mark(skip);
        } else {
            emitNullJump(asm, type, dup, whenNull, labels.get(ins.operand(0)));
        }
        break;
    }
```

with the helper (natural home: `BranchCondition`, beside `emitJump`, or `CompilerUtil`):

```java
static void emitNullJump(
        InstructionAdapter asm, ValType type, boolean dup, boolean whenNull, Label target) {
    if (dup) {
        asm.dup();
    }
    if (type.isObjectRef()) {
        asm.visitJumpInsn(whenNull ? Opcodes.IFNULL : Opcodes.IFNONNULL, target);
    } else {
        asm.iconst(REF_NULL_VALUE);
        asm.visitJumpInsn(whenNull ? Opcodes.IF_ICMPEQ : Opcodes.IF_ICMPNE, target);
    }
}
```

Note the `dup` must happen **before** the jump in both the forward and the back-edge case, and the
jump consumes the duplicate — leaving `[ref]` on both paths, exactly as the unfused sequence did.
The `DROP` that `br_on_null` emits after the `IFEQ` is a separate instruction and stays untouched.

### 2.4 Resulting bytecode

`br_on_null $l`, GC ref:

```
  DUP                       DUP
  IFNULL L_isNull           IFNONNULL L_notNull
  ICONST_0                  POP
  GOTO L_end        ==>     GOTO L_l
L_isNull:                 L_notNull:
  ICONST_1
L_end:
  IFEQ L_notNull
  POP
  GOTO L_l
L_notNull:
```

Eight instructions and three labels down to four and one.

`if (ref.is_null x)`, GC ref: `INVOKESTATIC Shaded.isGcRefNull; IFEQ L_else` → `IFNONNULL L_else`.

### 2.5 Steps

1. `CompilerOpCode`: add `REF_NULL_JUMP` with the operand comment.
2. `CompilerInstruction.labelTargets()`: add the case next to `COND_JUMP`.
3. `BranchCondition` (or `CompilerUtil`): add `emitNullJump`.
4. `WasmAnalyzer.fuseBranchConditions`: switch the look-back to the instruction, add the three
   producers, emit `REF_NULL_JUMP`.
5. `Compiler`: add the `REF_NULL_JUMP` case.
6. Tests (§4), then re-approve the snapshots.

Expect `BR_ON_NULL_CHECK` / `BR_ON_NON_NULL_CHECK` to become dead in practice but **keep them**:
the pass only fires on adjacency, and a future lowering change could separate the pair.

## 3. Phase 2 — `ref.eq` fusion

### 3.1 The constraint

`IF_ACMPEQ` implements `refEq` only when neither operand can be a `WasmI31Ref` (§1.3). Null is
fine (`null == null` is identity-true, matching `refEq`'s first branch).

So: fuse only when the analyzer's static operand types rule out `i31ref`. At the `REF_EQ` site,
`WasmAnalyzer` already knows both popped `ValType`s. A concrete struct/array type index rules i31
out; `eqref`, `anyref` and `i31ref` itself do not. A conservative predicate (`canBeI31(ValType)`,
new, next to the existing ref-type helpers) keeps the default safe: if in doubt, do not fuse.

Two operands are involved, so the check is `!canBeI31(a) || !canBeI31(b)` — one non-i31 operand is
enough, because `refEq`'s i31 branch requires *both* to be `WasmI31Ref`.

### 3.2 Implementation

Cleaner than Phase 1, because the test is representation-independent — this one *is* a
`BranchCondition`:

```java
REF_EQ(Opcodes.NOP, Opcodes.IF_ACMPEQ),
```

`negate()` must learn the new base. `IF_ACMPEQ` (165) / `IF_ACMPNE` (166) are an adjacent pair, so
the existing trick extends:

```java
private static int negate(int jump) {
    int base;
    if (jump >= Opcodes.IFNULL) {          // IFNULL / IFNONNULL, if Phase 1 routes through here
        base = Opcodes.IFNULL;
    } else if (jump >= Opcodes.IF_ACMPEQ) {
        base = Opcodes.IF_ACMPEQ;
    } else if (jump >= Opcodes.IF_ICMPEQ) {
        base = Opcodes.IF_ICMPEQ;
    } else {
        base = Opcodes.IFEQ;
    }
    return base + ((jump - base) ^ 1);
}
```

Beware the ordering: `IF_ACMPEQ` (165) > `IF_ICMPEQ` (159), so the `IF_ACMPEQ` test must come
before the `IF_ICMPEQ` one. Add a unit test that round-trips `negate(negate(x)) == x` over every
jump opcode the enum uses — cheap insurance for a bit trick that is now four-way.

Since `ofComparison` maps `CompilerOpCode → BranchCondition` with no extra context, the i31 gate
cannot live there. Two options:

- **(a) gate at lowering (preferred).** Where `WasmAnalyzer` emits `REF_EQ`, check the operand
  types and emit a new `REF_EQ_FUSABLE` opcode when i31 is excluded, otherwise the existing
  `REF_EQ`. `ofComparison` then maps only `REF_EQ_FUSABLE`. Both lower identically when *not*
  fused, so `EmitterMap` registers both to `Emitters::REF_EQ`.
- **(b) gate in the pass.** Requires threading the value-stack types into `fuseBranchConditions`,
  which currently runs on the finished list with no type context. More plumbing, no benefit.

Take (a).

### 3.3 Steps

1. `WasmAnalyzer`: add `canBeI31(ValType)`; emit `REF_EQ_FUSABLE` at the `ref.eq` site when both
   operand types allow it.
2. `CompilerOpCode` / `EmitterMap`: add `REF_EQ_FUSABLE`, registered to `Emitters::REF_EQ`.
3. `BranchCondition`: add the `REF_EQ` entry, extend `negate`, add it to `ofComparison`.
4. Tests (§4).

Everything else — `COND_JUMP`, `SELECT_COND`, the emitters — works unchanged, so `select` on
`ref.eq` comes along for free here (unlike Phase 1).

## 4. Tests

Model on `BranchConditionTest` and the `branch_conditions.wat` matrix.

**New fixture** `wasm-corpus/src/main/resources/wat/ref_conditions.wat`, exports crossing each
condition with each consumer shape:

- conditions: `ref.is_null` on a GC ref, `ref.is_null` on a `funcref` (the int-encoded path),
  `br_on_null`, `br_on_non_null`, `ref.eq` on two concrete struct refs (fusable), `ref.eq` on
  `eqref`/`i31ref` (must **not** fuse);
- shapes: `br_if`, `br_if_eqz`, `br_if_eqz2`, `if`, `if_eqz`, `select`, `loop_direct` /
  `loop_eqz` (backward jump → `checkInterruption` path), `br_if_unwind`;
- operands: null and non-null for every ref type, plus for `ref.eq` two distinct `i31ref`s with
  equal payloads — the case that catches an `IF_ACMPEQ` shortcut taken too eagerly.

`br_on_null.wat` and `gc_review_fixes.wat` in the same directory show the module boilerplate and
the test wiring to copy. Regenerate the `.wasm` beside it in
`wasm-corpus/src/main/resources/compiled/ref_conditions.wat.wasm` the same way
`branch_conditions.wat.wasm` was produced (`wat2wasm` from the `wasm-tools` module, GC and
reference-type features enabled) — confirm the exact invocation before committing the binary.

**`RefConditionTest`** (new, beside `BranchConditionTest`), two tests in the same style:

1. *differential* — every export run under the interpreter and the compiler over null / non-null /
   equal-payload-i31 operand combinations, `assertArrayEquals` on the results;
2. *structural* — every fusable export contains `REF_NULL_JUMP` (Phase 1) or `COND_JUMP` (Phase 2)
   and no longer contains `REF_IS_NULL` / `BR_ON_NULL_CHECK` / `BR_ON_NON_NULL_CHECK` /
   `REF_EQ_FUSABLE`; every non-fusable export (plain `eqref` `ref.eq`) still contains `REF_EQ`.

**Approval snapshots** — anything under `compiler/src/test/resources/run/endive/approvals/` that
covers GC or null-check code will shift. Re-approve and read the diffs: every changed hunk should
be a null-check or `refEq` collapsing, nothing else.

**Existing suites** — `runtime-tests`, `machine-tests` and the GC parts of the spec testsuite are
the real safety net for the i31 constraint; run them before trusting the fixture.

## 5. Validation and measurement

- Bytecode-size delta on a GC-heavy module (Java/Kotlin-to-Wasm output, or `gc_review_fixes.wat`):
  count `INVOKESTATIC` and total method bytes before/after.
- `jmh/` micro-benchmarks for the null-check path, since Phase 1 removes branches rather than just
  a call and should be measurable even after JIT warm-up.
- Same caveat as the numeric fusion: record the numbers, do not assume them.

## 6. Out of scope

- `ref.test` / `br_on_cast` — the runtime type check genuinely needs its helper; only the
  *boolean test* that follows it could fuse. Separate, smaller win.
- Unsigned integer comparisons — the remaining numeric gap, with a real trade-off (bias with
  `x ^ MIN_VALUE` plus a signed jump versus the current call). Own plan, own measurement.
- `select` on null conditions (Phase 1) — add only if the structural test shows real modules hit
  it.
- Value-form `ref.is_null` / `ref.eq` (result stored, returned or used arithmetically) — keeps the
  helper call, which is correct; inlining it would re-create the branch-to-make-a-constant pattern
  Phase 1 removes.
