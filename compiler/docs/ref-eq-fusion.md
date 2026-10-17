# Sound reference types, then `ref.eq` fusion (fork plan §3.3)

Status: implemented on `fuse-branch-conditions` (`50a7c320`), 2026-10-18. This is Phase 2 of
[ref-condition-fusion-plan.md](ref-condition-fusion-plan.md).

## Why the typing came first

`Shaded.refEq` is not reference identity: two `WasmI31Ref` objects are equal when their values
are. `ref.eq` can therefore only become the JVM's `if_acmpeq` when one operand provably cannot
be an `i31ref`, and that proof comes from `WasmAnalyzer`'s static types. Those types have to be
*sound*: the static type of a value must be a supertype of everything it can hold at run time.
Two places were not:

1. **`select` / `select_t`** pushed the type of the second operand. `select_t` states its
   result type, which can be wider: `(select (result eqref) (ref.i31 …) (struct.new $s …) c)`
   was typed `(ref $s)`.
2. **Scope exits.** `TypeStack.exitScope` left the types of the fallthrough path on the stack.
   A block's result can also arrive by a `br`/`br_if` to its label (or, for `if`, from the other
   arm), so `(block (result eqref) (br_if 0 (ref.i31 …) c) drop (struct.new $s …))` was typed
   `(ref $s)` after its `end`.

Until now no decision depended on reference subtypes, so both were harmless. With `ref.eq`
fusion both produce wrong results, and the new fixture shows it: without the fixes,
`select_t_eq_if(1, 1)` and `block_merge_eq_if(1, 1)` fuse and return 0 for two equal `i31ref`s.

The fixes:

- `select_t` pushes its annotated type, resolved against the type section. Untyped `select`
  (numeric and vector values only) keeps the operand's type.
- `TypeStack` remembers each scope's declared result types and puts them on the stack when the
  scope is left. (Polymorphic scopes already restored declared types.)

Other consumers of the static types were audited: casts take the source type hint from the
stack (now correct after `select_t`, previously the second operand's type), `REF_IS_NULL` and
the table operations decide object-versus-`int` representation, which subtypes of one hierarchy
share; `br_on_cast`/`br_on_cast_fail` push the annotated cast and source types; locals,
globals, calls and struct/array accesses push declared types.

## `ref.eq` fusion

As planned (option (a), gate at lowering):

- `WasmAnalyzer.canBeI31(ValType)`: false for a concrete type index (struct, array or function
  type), `structref`, `arrayref` and `nullref`/`none`; true otherwise (`eqref`, `anyref`,
  `i31ref`, anything unexpected).
- At `ref.eq`, if either operand cannot be an `i31ref`, the analyzer emits `REF_EQ_FUSABLE`
  instead of `REF_EQ`. Both are emitted by `Emitters.REF_EQ` when not fused.
- `BranchCondition.REF_EQ` (`if_acmpeq`) is what `ofComparison` returns for `REF_EQ_FUSABLE`;
  `negate` learned the `IF_ACMPEQ`/`IF_ACMPNE` pair. `COND_JUMP` and `SELECT_COND` need no
  change, so `select` on `ref.eq` is fused too.

One operand suffices: `refEq`'s value comparison needs *both* to be `WasmI31Ref`s.

## Tests

`RefEqConditionTest` with `ref_eq.wat` (compiled with Endive's own `Wat2Wasm`, which reproduces
the existing `ref_conditions.wat.wasm` byte for byte): fusable pairs (`(ref null $s)` against
`eqref` or `(ref null $s)`) under `if`, `if` + `eqz`, `br_if` and `select`; non-fusable pairs
(`eqref` against `eqref`); the `select_t`, block-merge and if-merge regressions; null, two
distinct `i31ref`s of the same value, a different `i31ref`, two globals and fresh structs as
operands. Differential against the interpreter plus structural assertions on the analyzer
output; `BranchConditionTest` checks the new complement pair. The full spec suite
(`compiler-tests`, 34,121 tests) and `machine-tests` pass with the typing change.

## Effect

None on the six corpus modules (none uses GC `ref.eq` in a branch); this matters for GC
languages (Kotlin/Wasm, Dart, OCaml, Java via J2Wasm), where identity comparisons of structs
are common. The typing fixes are independent of fusion and are candidates for upstream on their
own: the cast source hint after `select_t` was wrong there as well.
