# Bugs in the boxed-argument ABI for functions with more than 253 parameter slots

Three related bugs in how the compiler handles functions whose parameters do not fit into a JVM
method signature. All three were reproduced against the interpreter as the reference, on
OpenJDK 21.0.12.1, at commit `f5225324` (branch `fuse-branch-conditions`). None of them is
caused by the branch-condition fusion; they predate it.

| # | Bug | Trigger | Symptom | Severity |
|---|---|---|---|---|
| 1 | Declared locals are not zero-initialized | > 253 param slots **and** a local read before it is written on some path | `VerifyError` from `Instance.build()`; the whole module fails to instantiate | Loud |
| 2 | Object-reference parameters become `null` | > 253 param slots **and** a GC ref or `externref` parameter | Wrong results, no error | **Silent** |
| 3 | `call_indirect` stub signature exceeds 255 slots | Any function type with 252 or 253 param slots in the type section | `IllegalArgumentException: bad parameter count 256/257` at compile time | Loud |

Contents:

1. [Background: the boxed-argument ABI](#1-background-the-boxed-argument-abi)
2. [Bug 1: locals are not zero-initialized](#2-bug-1-locals-are-not-zero-initialized)
3. [Bug 2: object references are dropped](#3-bug-2-object-references-are-dropped)
4. [Bug 3: 252- and 253-slot types break the call_indirect stubs](#4-bug-3-252--and-253-slot-types-break-the-call_indirect-stubs)
5. [Reproduction](#5-reproduction)
6. [Why the existing tests do not catch this](#6-why-the-existing-tests-do-not-catch-this)
7. [Impact on the function-splitting work](#7-impact-on-the-function-splitting-work)
8. [Fixes](#8-fixes)
9. [Upstream status](#9-upstream-status)

## 1. Background: the boxed-argument ABI

A compiled Wasm function becomes a static JVM method:

```
static <R> func_N(<p0>, <p1>, ..., Memory memory, Instance instance)
```

A JVM method descriptor may use at most 255 parameter slots (`long` and `double` take two).
`CompilerUtil` reserves two slots for `Memory` and `Instance`:

```java
// CompilerUtil.java:40
private static final int MAX_PARAMETER_COUNT = 253;

// CompilerUtil.java:177
public static boolean hasTooManyParameters(FunctionType type) {
    return type.params().stream().mapToInt(CompilerUtil::slotCount).sum() > MAX_PARAMETER_COUNT;
}
```

When a type has more than 253 parameter slots, the compiler switches that function to a boxed
ABI (introduced by upstream Chicory commit `723810ff`, "fix: #830 AOT
java.lang.IllegalArgumentException: bad parameter count", April 2025):

```
static <R> func_N(long[] args, Memory memory, Instance instance)
```

- **Callers** pack every argument into a fresh `long[]` (`Emitters.emitBoxValuesOnStack`).
- **The callee's prologue** unpacks the array into ordinary JVM locals, so the body can use the
  same `LOCAL_GET`/`LOCAL_SET` emitters as any other function.

The slot layout of such a method (`Context.java:65–101`):

| JVM slot | Contents |
|---|---|
| 0 | `long[] args` |
| 1 | `Memory` |
| 2 | `Instance` |
| 3 … 3+S−1 | the unpacked Wasm parameters (S = parameter slots) |
| 3+S … | the declared Wasm locals |
| … | temp slots, try/catch save slots |

All three bugs live in this path. Functions at or below 253 slots are not affected, except by
bug 3.

## 2. Bug 1: locals are not zero-initialized

### 2.1 The code

`Compiler.compileFunction`, `Compiler.java:2154–2186`:

```java
int localsCount = type.params().size();
if (hasTooManyParameters(type)) {
    // unbox the arguments from long[]
    for (int i = 0; i < type.params().size(); i++) {
        ...
        asm.store(ctx.localSlotIndex(i), asmType(param));
    }
    // since we just converted the arguments to long[].
    localsCount = 1;
}

// initialize local variables to their default values
localsCount += body.localTypes().size();
for (int i = type.params().size(); i < localsCount; i++) {
    var localType = localType(type, body, i);
    ...
    asm.store(ctx.localSlotIndex(i), asmType(localType));
}
```

`i` is a **Wasm local index**: parameters are `0 … P−1` and declared locals are `P … P+L−1`.
The loop must therefore run from `P` to `P + L`. Before `723810ff` it did:

```java
int localsCount = type.params().size() + body.localTypes().size();
for (int i = type.params().size(); i < localsCount; i++) { ... }
```

`723810ff` split the computation to account for the `long[]` that replaces the parameters in the
JVM signature, and reset `localsCount` to `1`. That mixes two numbering schemes: `1` counts the
single JVM parameter, but the loop bound is still compared against Wasm local indices. The upper
bound becomes `1 + L` instead of `P + L`.

### 2.2 Which locals are missed

With `P` parameters and `L` declared locals, the loop runs for local indices `P … L`, so:

- the first `max(0, L + 1 − P)` declared locals are initialized;
- the last `min(L, P − 1)` declared locals are **not** initialized.

On this path `P ≥ 127` (254 slots of `i64`/`f64` at two slots each), so for any realistic function
with fewer declared locals than parameters, **no local is initialized at all**. A function with
more locals than parameters has its first few locals initialized by accident, which is why
`c_first_local` in the reproduction passes while `c_last_local` fails.

### 2.3 What goes wrong

Wasm guarantees that every declared local starts at zero (or null), and compilers rely on it:
loop accumulators start at `0`, flags are set only on one branch, and so on. On the JVM an
unwritten local slot has verifier type `top`. `ClassWriter.COMPUTE_FRAMES` computes the stack map
frames faithfully, so any path that reads the slot before a store is rejected by the verifier:

```
java.lang.VerifyError: Bad local variable type
  Location:
    run/endive/$gen/CompiledMachineFuncGroup_0.func_0([JLrun/endive/runtime/Memory;Lrun/endive/runtime/Instance;)I @1899: wide
  Reason:
    Type top (current frame, locals[257]) is not assignable to integer
```

For an `i64`/`f64` local the message is different, but the cause is the same:

```
java.lang.VerifyError: get long/double overflows locals
  Reason:
    Local index 257 is invalid
```

(ASM derives `max_locals` from the frames it computes, so a two-slot local that is only ever
loaded is not counted. Writing the local first makes the same function verify; see
`d2_i64_written` below.)

The trigger is a read that is reachable before any write on at least one path:

- a loop that reads an accumulator before the first write (`local.get $acc` at the loop head);
- a local written in only one arm of an `if` and read after the join;
- a local that is only ever read.

A function that writes every local before reading it on every path verifies fine. That is why the
bug survived: generated code often, but not always, has this property.

### 2.4 Blast radius

The JVM verifies a class as a whole. Generated functions are grouped into
`CompiledMachineFuncGroup_<n>` classes of up to `DEFAULT_MAX_FUNCTIONS_PER_CLASS` = 12,288
functions (`Compiler.java:148`). One bad method makes the entire group unloadable.

In practice the error surfaces from `Instance.builder(...).build()`: **the whole module fails to
instantiate**, including functions that never call the broken one. The reproduction `i_blast`
adds an unrelated `(func (export "other") (result i32) (i32.const 1))`; `build()` throws before
`other` can be called.

The failure is loud: no code runs with a wrong value. For ahead-of-time compilation
(`build-time-compiler`, the Maven plugin) the classes are generated without error and the
`VerifyError` appears only at run time, when the module is instantiated.

## 3. Bug 2: object references are dropped

### 3.1 The cause

The boxed ABI passes arguments as `long[]` only. Numeric values and int-encoded references
(`funcref`) fit into a `long`; **object references do not**. `ValType.isObjectRef()` is true for
every GC reference type **and for `externref`** (`ValType.java:807–809`, since commit
`e1d0fd6b` "Host collectable GC references"). So this is not limited to Wasm GC modules; any
module that passes an `externref` to a function with more than 253 parameter slots is affected.

The ABI has no channel for these values, and every site that uses it drops them:

| Site | What happens to an object-ref argument |
|---|---|
| `Emitters.CALL` (`Emitters.java:402`, boxing at :407) | `emitBoxValuesOnStack` stores `0L` as a placeholder (`Emitters.java:231–236`) |
| `Emitters.CALL_INDIRECT` (:433) | same |
| `Emitters.CALL_REF` (:1751) | same |
| Callee prologue (`Compiler.java:2159–2161`) | `aconst_null`: "Object refs can't be stored in long[] — use null as default" |
| Export entry points `compileCallFunction` (:1393) and `compileCallWithRefsFunction` (:1486) | pass only `args` (slot 2); the `refArgs` array in slot 3 is ignored |
| Cross-module `call_indirect` stub (:1793) | `aconst_null` for `refArgs` |
| Interpreter fallback in `compileFunction` (:2104–2107) | `aconst_null` for `refArgs` |

### 3.2 What goes wrong

The callee always sees `null` for its reference parameters, whatever the caller passed. There is
no error: a `ref.is_null` returns `1`, a null check takes the null branch, and a dereference
traps with a null-reference error only if the code happens to dereference it.

Measured:

| Case | Interpreter | Compiled |
|---|---|---|
| `g_struct_param`: struct param; returns field 0, or `-1` if null | `5` | `-1` |
| `f_externref_param`: `ref.is_null` of a non-null host object | `0` | `1` |

This is the most dangerous of the three bugs, because the program keeps running with wrong
values.

Falling back to the interpreter does not avoid it: the interpreter-fallback stub in
`compileFunction` passes `null` for `refArgs` too.

## 4. Bug 3: 252- and 253-slot types break the call_indirect stubs

### 4.1 The cause

For every function type, the compiler generates a `call_indirect_<typeId>` dispatch method. Its
signature is the function's raw signature plus four extra parameters:

```java
// CompilerUtil.java:168
public static MethodType callIndirectMethodType(FunctionType functionType) {
    return rawMethodTypeFor(functionType)
            .appendParameterTypes(int.class, int.class, Memory.class, Instance.class);
}
```

`rawMethodTypeFor` switches to `long[]` only above 253 slots, the threshold computed for **two**
extra parameters. With four extra parameters, a type with 252 or 253 slots produces a descriptor
of 256 or 257 slots, and `MethodType.appendParameterTypes` throws:

```
java.lang.IllegalArgumentException: bad parameter count 256
java.lang.IllegalArgumentException: bad parameter count 257
```

This is the same error that `723810ff` was meant to fix (#830), left open for a two-slot window.

### 4.2 Scope

The stubs are generated for **every** function type in the type section (`Compiler.java:648–660`),
not just for types that are actually used by `call_indirect`. So compilation fails for any module
that contains a 252- or 253-slot function type, even if the function is only called directly or
only exported. `CALL_REF` uses the same method type (`Emitters.java:1760–1764`) and has the same
limit.

The failure is at compile time, so it is loud. It does not fall back to the interpreter.

## 5. Reproduction

Each case is a separate module: a function `$big` with the listed signature, called by an
exported `run` with zero arguments. The WAT files are generated by a script, since typing 254
parameters by hand is not practical. Non-GC modules were assembled with `wat2wasm --enable-all`;
`g_struct_param` needs Binaryen's `wasm-as --enable-gc --enable-reference-types`, because wabt
does not parse `struct.get`.

Generator core:

```python
def params(n, t='i32'): return ' '.join(f'(param {t})' for _ in range(n))
def args(n):            return ' '.join('(i32.const 0)' for _ in range(n))
P = 254

cases['a_loop_acc'] = f'''(module
 (func $big {params(P)} (result i32) (local $acc i32) (local $i i32)
  (loop $l
   (local.set $acc (i32.add (local.get $acc) (i32.const 1)))
   (local.set $i (i32.add (local.get $i) (i32.const 1)))
   (br_if $l (i32.lt_s (local.get $i) (i32.const 10))))
  (local.get $acc))
 (func (export "run") (result i32) (call $big {args(P)})))'''

cases['b_read_default'] = f'''(module
 (func $big {params(P)} (result i32) (local $x i32) (local.get $x))
 (func (export "run") (result i32) (call $big {args(P)})))'''

cases['g_struct_param'] = f'''(module
 (type $s (struct (field i32)))
 (func $big (param (ref null $s)) {params(P)} (result i32)
  (if (result i32) (ref.is_null (local.get 0)) (then (i32.const -1))
   (else (struct.get $s 0 (local.get 0)))))
 (func (export "run") (result i32) (call $big (struct.new $s (i32.const 5)) {args(P)})))'''

for n in (251, 252, 253):
    cases[f'e_call_indirect_{n}'] = f'''(module
 (type $t (func {params(n)} (result i32)))
 (table 1 funcref) (elem (i32.const 0) $big)
 (func $big (type $t) (i32.const 42))
 (func (export "run") (result i32) (call_indirect (type $t) {args(n)} (i32.const 0))))'''
```

The driver runs every module on `InterpreterMachine` and on `MachineFactoryCompiler::compile` and
compares the first result:

```java
var b = Instance.builder(Parser.parse(bytes));
b = compiled ? b.withMachineFactory(MachineFactoryCompiler::compile)
             : b.withMachineFactory(InterpreterMachine::new);
var r = b.build().export("run").apply(args);
```

The `externref` case uses `applyWithRefs(new long[] {0}, new Object[] {"host-object"})`, since
`apply` rejects functions with reference parameters.

Results:

| Case | Shape | Interpreter | Compiled |
|---|---|---|---|
| `a_loop_acc` | 254 i32 params, accumulator read at loop head | `10` | `VerifyError` |
| `b_read_default` | 254 params, local never written | `0` | `VerifyError` |
| `c2_cond_write` | local written in one `if` arm only | `0` | `VerifyError` |
| `c_first_local` | 254 params, 300 locals, read local 254 | `0` | `0` (initialized by accident) |
| `c_last_local` | 254 params, 300 locals, read local 553 | `0` | `VerifyError` |
| `d_i64_params` | 127 i64 params, i64 local never written | `0` | `VerifyError` (long overflow) |
| `d3_i64_two_locals` | 127 i64 params, i32 + i64 locals | `0` | `VerifyError` (long overflow) |
| `d2_i64_written` | as `d_i64_params`, local written first | `3` | `3` |
| `i_blast` | `b_read_default` plus an unrelated export | — | `build()` throws `VerifyError` |
| `e_call_indirect_251` | 251 slots, `call_indirect` | `42` | `42` |
| `e_call_indirect_252` | 252 slots, `call_indirect` | `42` | `IllegalArgumentException: bad parameter count 256` |
| `e_call_indirect_253` | 253 slots, `call_indirect` | `42` | `IllegalArgumentException: bad parameter count 257` |
| `j_direct_only_252/253` | direct call only | `42` | `IllegalArgumentException` |
| `k_exported_252/253` | exported, never called | `42` | `IllegalArgumentException` |
| `f_externref_param` | externref + 254 params, `ref.is_null` of non-null | `0` | **`1`** |
| `g_struct_param` | struct + 254 params | `5` | **`-1`** |
| `h_control_200` | `a_loop_acc` with 200 params (normal ABI) | `10` | `10` |

## 6. Why the existing tests do not catch this

- **`lots-of-args.wat`** (used by `ApprovalTest.verifyLotsOfArgs` and `CallTest`): the
  many-parameter function declares no locals, and its reference arguments are
  `(ref.null func)` and `(ref.null extern)`. A dropped null reference is still null, so the test
  passes even though the reference is never transferred.
- **`call_many_params_try.wat`** (`CallManyParamsTryTest`): exercises temp-slot sizing around a
  `try_table`, again without read-before-write locals or non-null references.
- No test uses a type with exactly 252 or 253 parameter slots.

## 7. Impact on the function-splitting work

None on the current thesis corpus. The splitter caps a helper boundary at 40 live-ins, so
generated helpers never approach 253 parameter slots, and none of the benchmark modules has such
a function of its own (`jvm-size.h` notes the corpus tops out at 18 Wasm parameters).

Two consequences are still worth recording:

- If the live-in cap is ever raised past roughly 250 slots, helpers would start failing on
  bug 1 immediately: split helpers routinely receive live-ins as parameters and declare fresh
  locals for their own temporaries.
- `jvm-size.h` models the 253-slot boxing threshold (`EndiveMaxParamSlots`) as Endive's
  behaviour. It implicitly assumes 252- and 253-slot helpers compile; because of bug 3 they do
  not.

## 8. Fixes

### 8.1 Bug 1 (verified)

Use the Wasm local index bound directly and drop the variable that mixed the two numbering
schemes:

```diff
-        int localsCount = type.params().size();
         if (hasTooManyParameters(type)) {
             // unbox the arguments from long[]
             for (int i = 0; i < type.params().size(); i++) {
                 ...
             }
-            // since we just converted the arguments to long[].
-            localsCount = 1;
         }

         // initialize local variables to their default values
-        localsCount += body.localTypes().size();
-        for (int i = type.params().size(); i < localsCount; i++) {
+        int localsEnd = type.params().size() + body.localTypes().size();
+        for (int i = type.params().size(); i < localsEnd; i++) {
```

Removing `localsCount` entirely is required: leaving it unused fails the project's checkstyle
`UnusedLocalVariable` rule. With this change applied, all eight bug-1 cases above match the
interpreter; the change was reverted afterwards and is not committed.

Regression test: add `a_loop_acc`, `b_read_default`, `c2_cond_write`, `c_last_local` and
`d_i64_params` to the corpus and compare against the interpreter, as `BranchConditionTest` does.

### 8.2 Bug 2

The boxed ABI needs a channel for references. When a boxed type has object-ref parameters, pass
`Object[] refArgs` next to `long[] args`:

```
static <R> func_N(long[] args, Object[] refArgs, Memory memory, Instance instance)
```

and update every site from the table in §3.1 consistently:

1. `rawMethodTypeFor` / `methodTypeFor` / `callIndirectMethodType`: add `Object[]` after
   `long[]` when `hasTooManyParameters(type) && type.hasObjectRefParams()`.
2. `Emitters.CALL`, `CALL_INDIRECT`, `CALL_REF`: use `emitBoxValuesOnStackWithRefs`, which already
   builds both arrays (it is used by `RETURN_CALL`).
3. `Context`: shift `memorySlot`/`instanceSlot` by one when the extra array is present.
4. `compileFunction` prologue: load object-ref parameters from `refArgs[i]` with a `checkcast`
   instead of `aconst_null`.
5. Entry points `compileCallFunction` / `compileCallWithRefsFunction`: forward `refArgs` (slot 3).
6. Cross-module stub and interpreter fallback: forward `refArgs` instead of `aconst_null`.
7. `TempSlotCalculators.call`/`callIndirect`/`callRef`: account for the extra array.

Until that lands, the compiler should refuse such a function with a clear error rather than
compile it with wrong semantics.

Regression tests: `f_externref_param` and `g_struct_param`, both with non-null arguments. Also
change `lots-of-args.wat` to pass a non-null `externref`, so the existing test covers the
transfer.

### 8.3 Bug 3

The `call_indirect_<type>` and `CALL_REF` paths need their own threshold, `255 − 4 = 251` slots:

1. Add `hasTooManyParameters(FunctionType type, int extraSlots)` and use it with `4` in
   `callIndirectMethodType`, `Emitters.CALL_INDIRECT`, `Emitters.CALL_REF`,
   `compileCallIndirect`, `compileCallIndirectApply` and the matching `TempSlotCalculators`.
2. For a 252- or 253-slot target, the stub receives a `long[]` but the target itself uses the
   unboxed signature. The stub must unpack the array before invoking `func_N`.

Alternatively, lower `MAX_PARAMETER_COUNT` to 251 so that every function whose type is boxed for
the stub is also boxed for the direct call. That is a smaller change, but it moves two more slot
counts onto the boxed path and changes the direct-call ABI (and `EndiveMaxParamSlots` in
`jvm-size.h` would have to follow).

Regression tests: `e_call_indirect_252/253`, `j_direct_only_252/253`, `k_exported_252/253`.

## 9. Upstream status

| Project | Bug 1 | Bug 2 | Bug 3 | How checked |
|---|---|---|---|---|
| Endive fork, `fuse-branch-conditions` (`f5225324`) | yes | yes | yes | reproduced (§5) |
| Endive upstream, `bytecodealliance/endive` HEAD `0af6bbb` (2026-09-30) | yes | yes | yes | reproduced: fresh clone built, same repro cases, same results |
| Chicory, `MasterThesis/Chicory/chicory` (`c20f22e2`, 2026-02-27) | yes | n/a | yes | code inspection only, not run |

**Endive upstream.** The code is identical to the fork (`Compiler.java:2175–2176` for bug 1,
`Compiler.java:2160` and `Emitters.java:216/264` for bug 2, `CompilerUtil.java:164–166` for
bug 3). Running the reproduction against a build of upstream HEAD gives the same results as §5:
`VerifyError` for `a_loop_acc`, `b_read_default`, `c_last_local` and `d_i64_params`;
`bad parameter count 256/257` for `e_call_indirect_252` and `j_direct_only_253`; `-1` instead of
`5` for `g_struct_param`; `1` instead of `0` for the externref case. The control case passes.

**Chicory.** Endive's compiler is a fork of Chicory's AOT compiler, and bugs 1 and 3 are
inherited from Chicory commit `723810ff`. The Chicory checkout contains the same
`localsCount` loop and the same `callIndirectMethodType` with `MAX_PARAMETER_COUNT = 253`, and
it also generates a `call_indirect` stub for every type (`Compiler.java:467`). The second local
checkout, `chicory-blocktype-fix` (`a01781a5`, 2026-07-26), has the same bug-1 code. Bug 2 does
not apply: Chicory has no `Object` representation for references (no `isObjectRef`, no
`refArgs`); all references are int-encoded and fit into `long[]`.

All three should be reported to Endive, and bugs 1 and 3 to Chicory as well, with the
reproduction cases above.
