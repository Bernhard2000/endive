<!--
Draft body for the upstream pull request from branch `pr/fuse-branch-conditions`
(upstream/main 1580f534 + e4105edf + 3ba75b28). Paste everything below the line.
Title suggestion: "Compiler: fuse conditions into the branches and selects that consume them"
Before opening, work through TODO.md in this directory.
-->

---

## Summary

The AOT compiler lowers almost every Wasm comparison to an `invokestatic` of an `OpcodeImpl`
helper that returns `0`/`1`, and the `if`/`br_if`/`select` consuming it then tests that int
against zero again. This PR adds a peephole pass at the end of `WasmAnalyzer.analyze` that
fuses an adjacent condition producer into its consumer, so the JVM tests the condition
directly:

```diff
 ILOAD 0
 ILOAD 1
-INVOKESTATIC run/endive/runtime/OpcodeImpl.I32_LT_S (II)I
-IFNE L1
+IF_ICMPLT L1
```

Commit 1 (`Fuse comparisons into the branches and selects that consume them`) adds the pass
for signed `i32`/`i64` and floating-point comparisons, `i64.eqz` and chains of `i32.eqz`.
Commit 2 (`Extend branch-condition fusion to constants, unsigned i64 and null tests`) widens
what counts as a condition:

| condition | before | after |
| --- | --- | --- |
| `i32` vs 0 / 1 / -1 | `ICONST_0; IF_ICMPLT` | `IFLT` (and `x < 1` → `IFLE`, …) |
| `i32.lt_u x 256` (power-of-two bounds) | `SIPUSH 256; INVOKESTATIC i32_lt_u; IFNE` | `BIPUSH 8; IUSHR; IFEQ` |
| `i32.lt_u x 0x80000000` | `LDC; INVOKESTATIC i32_lt_u; IFNE` | `IFGE` |
| `i64.lt_u` etc. | `INVOKESTATIC OpcodeImpl.I64_LT_U; IFNE` | `INVOKESTATIC Long.compareUnsigned; IFLT` |
| `i64.eqz (i64.extend_i32_u b)` (Go booleans) | two helper calls + `LCMP` | absorbed like `i32.eqz` |
| `ref.is_null` | `INVOKESTATIC isGcRefNull; IFEQ` | `IFNONNULL` (`ICONST_M1; IF_ICMP*` for `funcref`) |
| `br_on_null` / `br_on_non_null` | `DUP; IFNULL; ICONST_0; GOTO; ICONST_1; IFEQ; …` | `DUP; IFNONNULL; …` |
| `br_on_cast_fail` | cast check, then two branches to invert the result, then `IFEQ` | cast check, `IFNE` |

## Effect

All numbers are static, from compiling `wasm-corpus` modules with `Compiler.builder(module)`
on upstream main vs. this branch. Total bytecode of the generated `func_*` methods:

| module | main | this PR | Δ | methods > 8000 B (`HugeMethodLimit`) |
| --- | ---: | ---: | ---: | --- |
| `basic.dotnet.wasm` | 1,569,334 | 1,511,973 | −3.66 % | 15 → 14 |
| `quickjs-provider.javy-dynamic.wasm` | 1,076,795 | 1,033,998 | −3.97 % | 13 → 10 |
| `javy-demo.js.javy.wasm` | 1,008,574 | 968,790 | −3.94 % | 12 → 11 |
| `main.go.wasm` | 1,706,688 | 1,671,946 | −2.04 % | 13 → 12 |
| `greet-wasi.rs.wasm` | 78,820 | 76,554 | −2.87 % | 0 |

Comparison and null-test helper calls in the generated code drop by 70–88 %. For example,
.NET goes from 22,498 to 3,374, and Go from 18,662 to 2,327 (+1,815 calls to
`Long.compareUnsigned`). Methods above 8000 bytes are never JIT-compiled with the default
`-XX:+DontCompileHugeMethods`, and methods near that size run out of C2 inlining budget
(`DesiredMethodLimit`). So the main expected benefit is for large generated methods, where
the helpers are not inlined. For small, hot methods C2 already folds the helper away, and
little runtime change is expected.

Local timing (one laptop session, JDK 21, runtime compiler, fresh JVM per sample,
interleaved rounds, paired bootstrap 95 % CI). Both arms run identical WASI modules; the
"split" modules were produced by an out-of-tree Binaryen function-splitting pass that cuts
large functions below 8 kB:

| workload | upstream | this PR | Δ steady state (95 % CI) |
| --- | ---: | ---: | --- |
| Lua 5.4 (`2 a`) | 1,779 ms | 1,679 ms | −5.5 % [−6.9, −4.3] |
| Lua 5.4, split | 263 ms | 253 ms | −4.1 % [−7.0, −0.9] |
| SQLite, split | 1,887 ms | 1,709 ms | −9.5 % [−11.1, −7.7] |
| QuickJS, split | 8,307 ms | 7,696 ms | −6.4 % [−8.6, −5.3] |
| SQLite (hot `sqlite3_step` > 64 KB → interpreter fallback) | 15,324 ms | 15,345 ms | −0.2 % [−2.3, +2.3] |

Runtime compilation is up to about 2.5 % slower (around 20 ms on these modules). Endive's own
JMH suite (factorial, sieve, wat2wasm, dispatch) shows no difference beyond fork-to-fork noise.
Its compiled benchmarks are small enough that C2 already inlines the helpers, so the Perf
workflow on this PR will likely report "no change".

## Correctness notes for review

- **Adjacency only.** The pass runs on the finished `CompilerInstruction` list and only
  fuses a producer that immediately precedes its consumer. Jump targets are explicit `LABEL`
  instructions, so nothing can branch in between.
- **NaN.** Float conditions pick `fcmpg`/`dcmpg` for `lt`/`le` and `fcmpl`/`dcmpl` for
  `gt`/`ge`, so NaN makes the condition false on both the direct and the negated jump.
- **Loop back edges.** A fused jump to an already visited label keeps the
  `checkInterruption` call on the taken path. This is the same scheme as the existing `IFNE`
  case, now available to every condition.
- **Unsigned `i32` and the C2 `fold_compares` bug (JDK-8376400).** Unsigned `i32` comparisons
  with variable operands or general constants are deliberately **not** fused. Fusing them
  needs signed compares on `MIN_VALUE`-biased operands, which is exactly what C2 miscompiles
  on JDK 11–17, and what `MemCopyWorkaround` protects `I32_GE_U`/`I32_LT_U` from. The only
  unsigned `i32` rewrites are bias-free: sign tests (`IFLT`/`IFGE`) and `x >>> k` zero tests.
  Unsigned `i64` is fused through `Long.compareUnsigned`, because `fold_compares` only merges
  `int` compares (`IfNode::cmpi_folds` requires `CmpI`).
- **Reference representation.** `ref.is_null` picks `IFNULL` or the `int` null compare using
  the same `ValType.isObjectRef` check the existing `REF_IS_NULL` emitter uses.
- **Removed internal opcodes.** `BR_ON_NULL_CHECK`, `BR_ON_NON_NULL_CHECK` and
  `BR_ON_CAST_FAIL_CHECK` are gone. A generic `DUP` opcode is added. `br_on_null` is now
  `DUP; REF_IS_NULL; IFEQ`, which the pass fuses (and which is still correct unfused).
- `ref.eq` is not fused: `refEq` compares `i31ref` by value, so `IF_ACMPEQ` would need a
  static proof that an operand is not an `i31ref`.

## Tests

- `BranchConditionTest`: differential (interpreter vs. compiler) and structural tests over
  `branch_conditions.wat` and the new `branch_conditions_const.wat`, which crosses every `i32`
  comparison with 12 constants, at and around every boundary, in 4 consumer shapes. The
  structural test checks each rewrite against an independent oracle. A third test checks
  that every jump opcode is negated to its complement.
- `RefConditionTest` (new) over `ref_conditions.wat` (new): null tests on struct, `funcref`,
  `externref` and `anyref` references in every consumer shape, plus `br_on_null`,
  `br_on_non_null`, `br_on_cast` and `br_on_cast_fail`, including unwinding and loop back
  edges.
- Approval snapshots: four updated (each diff is a helper call plus test collapsing into one
  jump), `verifyRefConditions` added.
- Locally on JDK 21: `compiler`, `compiler-tests` (34,120 spec tests through the compiler,
  including all `SpecV1Gc*`), `machine-tests` and `wabt` pass. On Temurin 17.0.20.1,
  `compiler`, `wabt` (wat2wasm compiled by Endive) and `machine-tests` pass.

## AI assistance

<!-- Bytecode Alliance AI Tool Use Policy: keep, adjust or remove as appropriate — see TODO.md -->
This change was developed with the help of Claude Code (Anthropic). I reviewed the code,
tests and measurements, and the commits carry `Co-Authored-By` trailers.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
