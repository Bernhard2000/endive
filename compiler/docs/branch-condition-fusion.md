# Branch-condition fusion

Branch `perf/fuse-branch-conditions`, single commit `f5225324`
("Fuse comparisons into the branches and selects that consume them").

A peephole pass in the compiler that rewrites a Wasm comparison plus the `if` / `br_if` / `select`
consuming it into one native JVM conditional jump, instead of a helper call that materializes a
0/1 `i32` which is then immediately tested against zero.

Contents:

1. [Background: how a condition used to be compiled](#1-background-how-a-condition-used-to-be-compiled)
2. [The condition table: `BranchCondition`](#2-the-condition-table-branchcondition)
3. [The pass: `WasmAnalyzer.fuseBranchConditions`](#3-the-pass-wasmanalyzerfusebranchconditions)
4. [New compiler opcodes](#4-new-compiler-opcodes)
5. [Emission](#5-emission)
6. [Worked examples](#6-worked-examples)
7. [Why this speeds up execution](#7-why-this-speeds-up-execution)
8. [Tests](#8-tests)
9. [Limits and follow-ups](#9-limits-and-follow-ups)
10. [File map](#10-file-map)

## 1. Background: how a condition used to be compiled

Endive compiles a Wasm function in two stages:

1. `WasmAnalyzer.analyze(funcId)` walks the Wasm body, maintains the abstract value stack, and
   lowers each Wasm instruction into a flat `List<CompilerInstruction>` over `CompilerOpCode`.
   Control flow is already explicit at this level: `LABEL`, `GOTO`, `IFEQ`, `IFNE`, `SWITCH`.
2. `Compiler` walks that list and emits JVM bytecode through ASM's `InstructionAdapter`.
   Per-opcode emission is looked up in `EmitterMap`, which registers each opcode as either
   `.intrinsic(...)` (inline bytecode from `Emitters`) or `.shared(..., OpcodeImpl.class)`
   (an `INVOKESTATIC` into `run.endive.runtime.OpcodeImpl`).

Nearly every comparison is `.shared`, i.e. a runtime call returning `TRUE`/`FALSE` as an `int`:

```java
// EmitterMap.java
.shared(CompilerOpCode.I32_EQ, OpcodeImpl.class)
.shared(CompilerOpCode.I32_EQZ, OpcodeImpl.class)
.shared(CompilerOpCode.I32_GE_S, OpcodeImpl.class)
.intrinsic(CompilerOpCode.I32_GE_U, Emitters::I32_GE_U)
```

```java
// runtime/src/main/java/run/endive/runtime/OpcodeImpl.java
@OpCodeIdentifier(OpCode.I32_LT_S)
public static int I32_LT_S(int a, int b) {
    return a < b ? TRUE : FALSE;
}
```

The consumers of a condition are lowered by `WasmAnalyzer` like this:

| Wasm | lowering | site |
| --- | --- | --- |
| `if` | `IFEQ <labelFalse>` | `WasmAnalyzer.java:341` |
| `br_if`, no stack unwind | `IFNE <labelTrue>` | `WasmAnalyzer.java:361` |
| `br_if` with unwind | `IFEQ <labelFalse>` · unwind · `GOTO <labelTrue>` | `WasmAnalyzer.java:357` |
| `select` / `select_t` | `SELECT <valtype id>` | `WasmAnalyzer.java:717` |

```java
// WasmAnalyzer.java — BR_IF
case BR_IF:
    stack.pop(ValType.I32);
    var ifUnwind = unwindStack(functionType, body, ins, ins.labelTrue(), stack);
    if (ifUnwind.isPresent()) {
        result.add(new CompilerInstruction(CompilerOpCode.IFEQ, ins.labelFalse()));
        result.add(ifUnwind.get());
        result.add(new CompilerInstruction(CompilerOpCode.GOTO, ins.labelTrue()));
    } else {
        result.add(new CompilerInstruction(CompilerOpCode.IFNE, ins.labelTrue()));
    }
    break;
```

So `(br_if $b (i32.eq (local.get $a) (local.get $b)))` produced:

```
ILOAD 0
ICONST_1
INVOKESTATIC run/endive/runtime/OpcodeImpl.I32_EQ (II)I   ; compute 0/1
IFNE L1                                                    ; test it against 0 again
```

Three costs per conditional branch — a static call, a materialized boolean, and a redundant
compare-against-zero — on the single hottest code shape in most Wasm workloads.

After the change:

```
ILOAD 0
ICONST_1
IF_ICMPEQ L1
```

## 2. The condition table: `BranchCondition`

New file `compiler/src/main/java/run/endive/compiler/internal/BranchCondition.java`: an enum of
the 26 conditions a JVM jump can test directly. Each carries a compare **prefix** and the jump
opcode taken when the condition is **true**.

```java
enum BranchCondition {
    /** {@code x != 0}: the implicit condition of {@code if}, {@code br_if} and {@code select}. */
    I32_NEZ(Opcodes.NOP, Opcodes.IFNE),
    I32_EQ(Opcodes.NOP, Opcodes.IF_ICMPEQ),
    I32_NE(Opcodes.NOP, Opcodes.IF_ICMPNE),
    I32_LT_S(Opcodes.NOP, Opcodes.IF_ICMPLT),
    I32_GT_S(Opcodes.NOP, Opcodes.IF_ICMPGT),
    I32_LE_S(Opcodes.NOP, Opcodes.IF_ICMPLE),
    I32_GE_S(Opcodes.NOP, Opcodes.IF_ICMPGE),
    I64_EQZ(Opcodes.LCONST_0, Opcodes.IFEQ),
    I64_EQ(Opcodes.LCMP, Opcodes.IFEQ),
    ...
    F32_LT(Opcodes.FCMPG, Opcodes.IFLT),
    F32_GT(Opcodes.FCMPL, Opcodes.IFGT),
    ...
    F64_GE(Opcodes.DCMPL, Opcodes.IFGE);
```

The prefix is a tag, not literally emitted: `NOP` means "no prefix", `LCONST_0` means "push a long
zero, then `lcmp`" (that is `i64.eqz`), the rest name the compare instruction.

```java
void emitJump(InstructionAdapter asm, Label target, boolean whenTrue) {
    switch (prefix) {
        case Opcodes.NOP:      break;
        case Opcodes.LCONST_0: asm.lconst(0); asm.lcmp(); break;
        case Opcodes.LCMP:     asm.lcmp();                break;
        case Opcodes.FCMPL:    asm.cmpl(Type.FLOAT_TYPE); break;
        case Opcodes.FCMPG:    asm.cmpg(Type.FLOAT_TYPE); break;
        case Opcodes.DCMPL:    asm.cmpl(Type.DOUBLE_TYPE); break;
        case Opcodes.DCMPG:    asm.cmpg(Type.DOUBLE_TYPE); break;
        default: throw new IllegalStateException("Unexpected prefix: " + prefix);
    }
    asm.visitJumpInsn(whenTrue ? jumpIfTrue : negate(jumpIfTrue), target);
}
```

Both outcomes consume the operands, so the fused form leaves the stack exactly as the old
comparison + jump pair did.

Three details worth spelling out.

**Negation by bit flip.** `IFEQ..IFLE` and `IF_ICMPEQ..IF_ICMPLE` are laid out in the order
EQ, NE, LT, GE, GT, LE, so complements are adjacent pairs:

```java
// IFEQ..IFLE and IF_ICMPEQ..IF_ICMPLE are laid out as EQ, NE, LT, GE, GT, LE,
// so each opcode's complement differs from it only in the lowest bit of the offset.
private static int negate(int jump) {
    int base = jump >= Opcodes.IF_ICMPEQ ? Opcodes.IF_ICMPEQ : Opcodes.IFEQ;
    return base + ((jump - base) ^ 1);
}
```

**NaN semantics.** Wasm requires every float comparison to be false when an operand is NaN. The
prefix variant is chosen so the compare pushes the value that makes the jump fall through on NaN:
the `g` variant (`FCMPG`/`DCMPG`, NaN → `+1`) for LT/LE, the `l` variant (NaN → `-1`) for GT/GE,
either for EQ/NE. Because NaN is folded into the compare *result*, taking the negated jump stays
correct for NaN too — which matters, since `if` lowers to a jump on the false outcome.

**Operand order.** The `OpcodeImpl` helpers take `(a, b)` with `a` the deeper stack slot, which is
exactly JVM `if_icmp<cond>`'s `value1 <cond> value2`. (`I32_EQ(int b, int a)` names its parameters
reversed but is symmetric.) So fusion never has to swap operands.

`ofComparison` maps a comparison opcode to its condition and returns empty for everything else:

```java
/**
 * Returns the condition computed by a comparison instruction whose 0/1 result can be fused
 * into the jump consuming it. Unsigned comparisons are not fused: without a JVM unsigned
 * compare they would still need a helper call, so fusing saves nothing. {@code i32.eqz} is
 * not listed: it is absorbed by negating the jump instead.
 */
static Optional<BranchCondition> ofComparison(CompilerOpCode opcode) {
    switch (opcode) {
        case I32_EQ:   return Optional.of(I32_EQ);
        ...
        case F64_GE:   return Optional.of(F64_GE);
        default:       return Optional.empty();
    }
}
```

Ids are enum ordinals, because `CompilerInstruction` operands are `long`s:

```java
static BranchCondition fromId(long id) { return VALUES[(int) id]; }
long id() { return ordinal(); }
```

## 3. The pass: `WasmAnalyzer.fuseBranchConditions`

Runs once at the end of `analyze`, over the finished instruction list, before temp slots are
computed:

```java
result = fuseBranchConditions(result);

return new AnalysisResult(
        result, computeMaxTempSlots(result, EmitterMap.TEMP_SLOT_CALCULATORS));
```

The pass is a linear scan that looks back into the already-emitted output:

```java
static List<CompilerInstruction> fuseBranchConditions(List<CompilerInstruction> in) {
    List<CompilerInstruction> out = new ArrayList<>(in.size());
    for (var ins : in) {
        var opcode = ins.opcode();
        if (opcode != CompilerOpCode.IFEQ
                && opcode != CompilerOpCode.IFNE
                && opcode != CompilerOpCode.SELECT) {
            out.add(ins);
            continue;
        }

        // IFEQ jumps when its operand is zero; IFNE and SELECT's "keep first" when non-zero
        var condition = BranchCondition.I32_NEZ;
        boolean whenTrue = opcode != CompilerOpCode.IFEQ;
        boolean fused = false;
        while (!out.isEmpty()) {
            var prev = out.get(out.size() - 1).opcode();
            if (prev == CompilerOpCode.I32_EQZ) {
                out.remove(out.size() - 1);
                whenTrue = !whenTrue;
                fused = true;
                continue;
            }
            var comparison = BranchCondition.ofComparison(prev);
            if (comparison.isPresent()) {
                out.remove(out.size() - 1);
                condition = comparison.get();
                fused = true;
            }
            break;
        }

        if (!fused) {
            out.add(ins);
        } else if (opcode == CompilerOpCode.SELECT) {
            out.add(new CompilerInstruction(
                    CompilerOpCode.SELECT_COND, ins.operand(0), condition.id(), whenTrue ? 1 : 0));
        } else {
            out.add(new CompilerInstruction(
                    CompilerOpCode.COND_JUMP, ins.operand(0), condition.id(), whenTrue ? 1 : 0));
        }
    }
    return out;
}
```

Rules, and why they hold:

- **Adjacency only.** A fusion is legal only if no control flow can enter between the comparison
  and its consumer. Jump targets in this IR are explicit `LABEL` instructions, so if the
  comparison is the immediately preceding instruction, nothing can branch in between and no other
  consumer can observe the boolean.
- **`i32.eqz` is absorbed, not fused.** Each one inverts `whenTrue`; a chain collapses, so
  `eqz eqz` cancels out. This also covers `i32.eqz` with no comparison in front: it fuses into a
  plain inverted zero test, visible in the approval diffs as
  `INVOKESTATIC OpcodeImpl.I32_EQZ; IFNE L0` → `IFEQ L0`.
- **Unsigned comparisons are excluded.** The JVM has no unsigned compare, so `I32_LT_U` and
  friends need `Integer.compareUnsigned` regardless; fusing would save nothing. They keep the old
  lowering plus their `IFEQ`/`IFNE`.
- **The internal `IFEQ`s stay safe.** `WasmAnalyzer` also emits `IFEQ` for `ref.is_null`,
  `br_on_null`, `br_on_cast` and catch-handler matching (`WasmAnalyzer.java:553`, `:583`, `:635`,
  `:685`, `:923`, `:932`). Each is preceded by its own producing instruction, never by a Wasm
  comparison opcode, so `ofComparison` returns empty and they pass through untouched — they keep
  testing a real reference/`instanceof` result.
- **Fusion is unconditional when it applies.** There is no heuristic and no flag; the rewrite is
  strictly smaller and strictly cheaper, so it always fires.

## 4. New compiler opcodes

```java
// CompilerOpCode.java
IFEQ,
IFNE,
SWITCH,
// fused comparison + conditional jump: [label, BranchCondition id, jump when true (0/1)]
COND_JUMP,
// fused comparison + select: [ValType id, BranchCondition id, keep first when true (0/1)]
SELECT_COND,
```

`CompilerInstruction.labelTargets()` needs a special case. For `GOTO`/`IFEQ`/`IFNE`/`SWITCH` every
operand is a label, but `COND_JUMP` carries two non-label operands — returning all three would
make `Compiler` allocate ASM labels for the condition id and the flag:

```java
public long[] labelTargets() {
    switch (opcode) {
        case GOTO:
        case IFEQ:
        case IFNE:
        case SWITCH:
        case TRY_CATCH_BLOCK:
            return operands;
        case COND_JUMP:
            return new long[] {operands[0]};
        default:
            return EMPTY;
    }
}
```

`SELECT_COND` needs no entry: it holds no label.

## 5. Emission

### 5.1 `COND_JUMP`

Handled in `Compiler`'s main instruction switch, mirroring the existing `IFNE` treatment of
backward jumps. A jump to an already-visited label is a loop back edge, which must run
`checkInterruption`; the condition is therefore emitted with the **negated** outcome jumping over
the call:

```java
case COND_JUMP:
    {
        var condition = BranchCondition.fromId(ins.operand(1));
        boolean whenTrue = ins.operand(2) != 0;
        if (visitedTargets.contains(ins.operand(0))) {
            Label skip = new Label();
            condition.emitJump(asm, skip, !whenTrue);
            emitInvokeStatic(asm, CHECK_INTERRUPTION);
            asm.goTo(labels.get(ins.operand(0)));
            asm.mark(skip);
        } else {
            condition.emitJump(asm, labels.get(ins.operand(0)), whenTrue);
        }
        break;
    }
```

This is strictly more general than the opcodes it replaces. Plain `IFEQ` still rejects a visited
target, because the `if`-lowering never produces a backward one:

```java
case IFEQ:
    if (visitedTargets.contains(ins.operand(0))) {
        throw new WasmEngineException("Unexpected backward jump");
    }
    asm.ifeq(labels.get(ins.operand(0)));
    break;
```

`COND_JUMP` subsumes both `IFEQ`- and `IFNE`-derived branches, so it handles the back-edge case
for all of them.

### 5.2 `SELECT_COND`

Registered next to the existing select:

```java
// EmitterMap.java
.intrinsic(CompilerOpCode.SELECT, Emitters::SELECT)
.intrinsic(CompilerOpCode.SELECT_COND, Emitters::SELECT_COND)
```

and identical to `Emitters.SELECT` except that the leading `ifne` becomes the fused condition:

```java
public static void SELECT(Context ctx, CompilerInstruction ins, InstructionAdapter asm) {
    var type = valType(ins.operand(0), ctx);
    var endLabel = new Label();
    asm.ifne(endLabel);                      // condition non-zero: keep the first value
    if (slotCount(type) == 1) {
        asm.swap();
    } else {
        asm.dup2X2();
        asm.pop2();
    }
    asm.mark(endLabel);
    emitPop(asm, type);                      // drop the value that lost
}

public static void SELECT_COND(Context ctx, CompilerInstruction ins, InstructionAdapter asm) {
    var type = valType(ins.operand(0), ctx);
    var condition = BranchCondition.fromId(ins.operand(1));
    var endLabel = new Label();
    condition.emitJump(asm, endLabel, ins.operand(2) != 0);
    if (slotCount(type) == 1) {
        asm.swap();
    } else {
        asm.dup2X2();
        asm.pop2();
    }
    asm.mark(endLabel);
    emitPop(asm, type);
}
```

The stack shape is unchanged: if the condition keeps the first value, jump straight to the end
label; otherwise reorder so the surviving value is on top (`swap` for one-slot types,
`dup2_x2; pop2` for `i64`/`f64`), then `emitPop` discards the loser (`POP` or `POP2`, from
`CompilerUtil`).

## 6. Worked examples

### 6.1 `if` on a signed comparison (real, from the approval tests)

`ApprovalTest.verifyExceptions.approved.txt`:

```diff
     ILOAD 0
     ICONST_1
-    INVOKESTATIC run/endive/runtime/OpcodeImpl.I32_EQ (II)I
-    IFEQ L5
+    IF_ICMPNE L5
```

`if` jumps to the else-branch on the *false* outcome, so the fused jump is the negation of
`I32_EQ`'s `IF_ICMPEQ`.

### 6.2 A bare `i32.eqz` condition (real)

`ApprovalTest.verifyTailCall.approved.txt`:

```diff
     ILOAD 0
-    INVOKESTATIC run/endive/runtime/OpcodeImpl.I32_EQZ (I)I
-    IFEQ L0
+    IFNE L0
```

No comparison to fuse, but the `eqz` flips `whenTrue`, so the whole thing collapses to a single
`IFNE` on the raw value.

### 6.3 A loop back edge (real)

`ApprovalTest.verifyIterFact.approved.txt` — the same collapse happens twice, on the loop entry
guard and on the back edge, the second one keeping its interruption check:

```diff
     IADD
     DUP
     ISTORE 0
-    INVOKESTATIC run/endive/runtime/OpcodeImpl.I32_EQZ (I)I
-    IFNE L0
+    IFEQ L0
     INVOKESTATIC run/endive/$gen/CompiledMachineShaded.checkInterruption ()V
     GOTO L1
```

Read against the `COND_JUMP` emitter: the loop target `L1` is already visited, so the fused
condition jumps to the `skip` label `L0` on the negated outcome, and falls through into
`checkInterruption` + `GOTO L1` when the branch is taken.

### 6.4 Instruction list before and after the pass

For `(br_if $b (i32.eqz (i32.lt_s (local.get $a) (local.get $b))))`
(fixture export `i32_lt_s_br_if_eqz`), `WasmAnalyzer` produces, then rewrites:

```
LOCAL_GET a          LOCAL_GET a
LOCAL_GET b    ==>   LOCAL_GET b
I32_LT_S             COND_JUMP [L_b, I32_LT_S, whenTrue=0]
I32_EQZ
IFNE L_b
```

`IFNE` starts at `whenTrue = true`; the `i32.eqz` flips it to `false`; `I32_LT_S` supplies the
condition. The emitter then writes `IF_ICMPGE L_b` — the complement of `IF_ICMPLT`.

### 6.5 Shapes for the other operand types

Derived from `emitJump` (not copied from a test snapshot):

| Wasm | emitted |
| --- | --- |
| `br_if` on `i64.lt_s` | `LLOAD a; LLOAD b; LCMP; IFLT L` |
| `if` on `i64.lt_s` | `LLOAD a; LLOAD b; LCMP; IFGE L_else` |
| `br_if` on `i64.eqz` | `LLOAD a; LCONST_0; LCMP; IFEQ L` |
| `br_if` on `f64.lt` | `DLOAD a; DLOAD b; DCMPG; IFLT L` (NaN → `+1` → not taken) |
| `if` on `f64.lt` | `DLOAD a; DLOAD b; DCMPG; IFGE L_else` (NaN → `+1` → taken, i.e. else) |
| `br_if` on `f32.gt` | `FLOAD a; FLOAD b; FCMPL; IFGT L` (NaN → `-1` → not taken) |

### 6.6 A wide `select` with a fused condition

`(select (i64.const 1) (i64.const 0) (i32.eq (local.get $a) (local.get $b)))`
(fixture export `i32_eq_select_wide`), derived from `SELECT_COND`:

```
LCONST_1                 ; first value
LCONST_0                 ; second value
ILOAD 0                  ; condition operands, left unmaterialized
ILOAD 1
IF_ICMPEQ L_end          ; condition true: first value already deeper -> keep it
DUP2_X2                  ; else bring the second value below the first
POP2
L_end:
POP2                     ; drop the loser
```

## 7. Why this speeds up execution

Removed from the hot path, per fused branch:

1. an `INVOKESTATIC` into `OpcodeImpl`,
2. materialization of a 0/1 `int`,
3. a second compare-against-zero.

Why that matters beyond raw instruction count:

- **JIT branch shape.** C2 and Graal see `if_icmp<cond>` (or `lcmp`/`fcmp` + `if<cond>`) directly
  and profile it as a real branch. Previously they had to inline the helper and constant-fold the
  boolean away to recover the same branch — usually achievable, but dependent on inlining budget
  and lost in large methods where inlining is throttled.
- **Bytecode size.** The fused form is 1–4 bytes where the old form was 7–8 (`invokestatic` plus
  jump). Endive packs whole modules into few large classes, so method size feeds directly into the
  JIT's own limits (`DontCompileHugeMethods` thresholds, inlining budgets) and class-loading cost
  — the same size cliff the function-splitting work targets.
- **Pre-JIT execution.** In the JVM interpreter and tier-0 the call is paid in full on every
  execution, so cold and short-lived code benefits immediately, with no warm-up.
- **Loop back edges.** A comparison or `i32.eqz` feeding a `br_if` is the standard loop-exit
  shape, executed once per iteration; `verifyIterFact` above shows exactly that collapsing.

The branch carries no benchmark numbers — the case is the instruction-count/code-size argument
plus the JIT-shape argument. Measuring on `jmh/` or the thesis benchmark harness is still open.

## 8. Tests

**`BranchConditionTest`** (new, `compiler/src/test/java/run/endive/compiler/internal/`) over the
fixture `wasm-corpus/src/main/resources/wat/branch_conditions.wat` — 330 exported functions — and
its compiled `wasm-corpus/src/main/resources/compiled/branch_conditions.wat.wasm`.

The fixture is a matrix: every fusable comparison, plus the unsigned ones, plus a `plain` row
whose condition is a bare `local.get`, each crossed with the consumer shapes `br_if`,
`br_if_eqz`, `br_if_eqz2` (double negation), `if`, `if_eqz`, `select`, `select_eqz`,
`select_wide` (an `i64` select, exercising `dup2_x2; pop2`), `loop_direct` and `loop_eqz`
(backward jump, exercising the `checkInterruption` path), and `br_if_unwind` (`br_if` with a
non-empty block result, exercising the `IFEQ` + unwind + `GOTO` lowering):

```wat
(func (export "i32_lt_s_br_if") (param $a i32) (param $b i32) (result i32)
  (block $b (br_if $b (i32.lt_s (local.get $a) (local.get $b))) (return (i32.const 1))) (i32.const 0))
(func (export "i32_lt_s_br_if_eqz2") (param $a i32) (param $b i32) (result i32)
  (block $b (br_if $b (i32.eqz (i32.eqz (i32.lt_s (local.get $a) (local.get $b))))) (return (i32.const 1))) (i32.const 0))
(func (export "i32_lt_s_select_wide") (param $a i32) (param $b i32) (result i32)
  (i32.wrap_i64 (select (i64.const 1) (i64.const 0) (i32.lt_s (local.get $a) (local.get $b)))))
(func (export "i32_lt_s_loop_direct") (param $a i32) (param $b i32) (result i32) (local $n i32)
  (loop $l (local.set $n (i32.add (local.get $n) (i32.const 1)))
    (if (i32.sub (i32.const 2) (local.get $n)) (then (br_if $l (i32.lt_s (local.get $a) (local.get $b))))))
  (local.get $n))
```

**Differential test** — every export runs under both the interpreter and the compiler over a cross
product of edge-case operands (`0, ±1, 2, MIN_VALUE, MAX_VALUE` for integers; `±0.0, 1.0, -1.5,
NaN, ±Infinity` for floats), so signedness, zero and the NaN rules are checked against the
reference implementation:

```java
for (var name : exportNames(module)) {
    var values = valuesFor(name);
    for (long a : values) {
        for (long b : values) {
            var expected = interpreted.export(name).apply(a, b);
            var actual = compiled.export(name).apply(a, b);
            assertArrayEquals(expected, actual, name + "(" + a + ", " + b + ") differs");
        }
    }
}
```

**Structural test** — asserts the pass actually fired where it should, and only there:

```java
boolean unsigned = name.contains("_u_");
boolean fused =
        opcodes.contains(CompilerOpCode.COND_JUMP)
                || opcodes.contains(CompilerOpCode.SELECT_COND);
// unsigned and plain i32 conditions have nothing to fuse unless an eqz sits in between
boolean fusible = name.contains("eqz") || !(unsigned || name.startsWith("i32_plain_"));
assertEquals(fusible, fused, name + " fused jump: " + opcodes);
assertEquals(
        unsigned,
        opcodes.stream().anyMatch(BranchConditionTest::isComparison),
        name + " comparisons: " + opcodes);
assertTrue(!opcodes.contains(CompilerOpCode.I32_EQZ), name + " kept eqz: " + opcodes);
```

**Approval snapshots updated**: `verifyBranching`, `verifyExceptions`, `verifyIterFact`,
`verifyTailCall` — 18 lines removed, 8 added, every diff a helper call plus test collapsing into
one jump.

## 9. Limits and follow-ups

- **Unsigned comparisons are not fused.** They could be, by biasing both operands
  (`x ^ Integer.MIN_VALUE` / `x ^ Long.MIN_VALUE`) and then using the signed jump — two `ixor`s
  plus a jump against a call. Worth measuring rather than assuming.
- **Only directly adjacent pairs fuse.** A comparison separated from its consumer by an unrelated
  instruction (a `local.set` scheduled in between, say) falls back to the old lowering. A small
  dependency-aware reordering, or tracking a "pending condition" across side-effect-free
  instructions, would widen coverage.
- **`br_table` is untouched** — it consumes an index, not a boolean.
- **Comparisons whose result is a real value** (stored to a local, returned, used arithmetically)
  still go through `OpcodeImpl`. That is correct: only consumers that immediately test the boolean
  are rewritten.
- **The internal boolean tests** (`ref.is_null`, `br_on_cast`, catch matching) could get the same
  treatment by giving their producers a `BranchCondition`-style fused form, e.g. `IFNULL`/`IFNONNULL`
  instead of a materialized null check.

## 10. File map

| File | Change |
| --- | --- |
| `compiler/src/main/java/run/endive/compiler/internal/BranchCondition.java` | new — 26-entry condition table, `emitJump`, `negate`, `ofComparison` |
| `compiler/src/main/java/run/endive/compiler/internal/WasmAnalyzer.java` | new `fuseBranchConditions` pass, called at the end of `analyze` |
| `compiler/src/main/java/run/endive/compiler/internal/CompilerOpCode.java` | new `COND_JUMP`, `SELECT_COND` |
| `compiler/src/main/java/run/endive/compiler/internal/CompilerInstruction.java` | `labelTargets()` special case for `COND_JUMP` |
| `compiler/src/main/java/run/endive/compiler/internal/Compiler.java` | `COND_JUMP` emission, incl. back-edge `checkInterruption` |
| `compiler/src/main/java/run/endive/compiler/internal/Emitters.java` | `SELECT_COND` emitter |
| `compiler/src/main/java/run/endive/compiler/internal/EmitterMap.java` | registers `SELECT_COND` |
| `compiler/src/test/java/run/endive/compiler/internal/BranchConditionTest.java` | new — differential + structural tests |
| `wasm-corpus/src/main/resources/wat/branch_conditions.wat` (+ compiled `.wasm`) | new — 330-function fixture matrix |
| `compiler/src/test/resources/run/endive/approvals/*.approved.txt` | four snapshots updated |
