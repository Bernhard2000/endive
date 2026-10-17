# Narrowing peephole and trivial helpers (fork plan §3.1)

Status: implemented on `fuse-branch-conditions` (`17542744`), 2026-10-18. Static census only;
the runtime effect is part of the fixed-runner campaign (thesis repo
`ci/run_endive_fork_ab.sh`).

## Problem

Many `EmitterMap` entries are `.shared(op, OpcodeImpl.class)`: an `invokestatic` of a one-line
helper. A call costs 3 bytes and a call, is paid in full in the interpreter and in methods C2
does not compile, and spends C2's inlining budget (`DesiredMethodLimit`, see
[inlining-at-8000-bytes.md](inlining-at-8000-bytes.md)). The census of the six corpus modules
(`data/compiler-census/`, `census.py --calls OpcodeImpl`) put one helper far ahead of all others:
`I64_EXTEND_I32_U`, 16,615 call sites in `main.go.wasm`.

The Go sites are not general zero-extensions. `wasm2wat` shows the shape behind 14,789 of them:

```wat
local.get $p  i64.extend_i32_u  i64.const 8  i64.add  i32.wrap_i64
```

Go models pointers as `i64` and narrows every address back to `i32`. The `i64` arithmetic is
dead weight: the low 32 bits of an `add` depend only on the low 32 bits of its operands.

## Design

`NarrowingPeephole` runs on the analyzer's instruction list, after analysis and before
branch-condition fusion. It rewrites adjacent instructions only; jump targets are explicit
`LABEL` instructions, so adjacency means no control flow enters a rewritten sequence.

| shape | rewritten to | saves |
| --- | --- | --- |
| `extend_i32_{u,s}; i64.const c; i64.{add,sub,mul,and,or,xor}; i32.wrap_i64` | `i32.const (int) c; i32.{op}` | 5 bytes and a call (`ldc2_w` + `ladd` + `l2i` + helper → `bipush` + `iadd`) |
| `i64.load{8,16,32}_{u,s}; i32.wrap_i64` | the `i32` load of the same width | 2 bytes (8/16 bit) or 6 bytes (32 bit, the `0xFFFFFFFF` mask) |
| comparison or `ref.is_null`; `i64.extend_i32_u` | `i64.extend_i32_s` (`i2l`) | 2 bytes and a call (the value is 0 or 1) |

Not rewritten, on purpose:

- a full `i64.load` followed by `i32.wrap_i64`. `i32.load` reads 4 bytes where `i64.load` reads
  8, so it traps for fewer addresses (5,112 Go sites stay as they are);
- shifts and rotations (the shift count is taken mod 64, not mod 32);
- `i64` operations whose result is used as `i64`.

Helpers that only delegate to one JVM instruction or one JDK method are now emitted as that
instruction or a direct call of the JDK method:

| helpers | emitted as |
| --- | --- |
| `F32/F64_CONVERT_I32/I64_S` | `i2f`, `l2f`, `i2d`, `l2d` |
| `I32_EXTEND_8_S`, `I32_EXTEND_16_S`; `I64_EXTEND_8/16/32_S` | `i2b`, `i2s`; `l2i` + `i2b`/`i2s` + `i2l`, `l2i` + `i2l` |
| `I32_CLZ/CTZ/POPCNT`, `I32_ROTL/ROTR` | `Integer.numberOfLeadingZeros` etc., `Integer.rotateLeft/Right` |
| `I64_CLZ/CTZ/POPCNT`, `I64_ROTL/ROTR` | `Long.*` + `i2l`; `l2i` + `Long.rotateLeft/Right` |
| reinterprets | `Float.floatToRawIntBits`, `Float.intBitsToFloat`, `Double.doubleToRawLongBits`, `Double.longBitsToDouble` |
| `F32_ABS`, `F64_ABS`, `F64_SQRT` | `Math.abs`, `Math.sqrt` |
| `I64_EXTEND_I32_U`, `F32/F64_CONVERT_I32_U` | `Integer.toUnsignedLong` (+ `l2f`/`l2d`) |

The JDK methods are C1/C2 intrinsics (except `Integer.toUnsignedLong`, which is a trivial
method), so they cost no inlining budget, and they have the same bytecode size as the helper
call. Seven of them are one byte larger (an added `l2i` or `i2l`), which the rule of the plan
allows ("inline form no larger than 4 bytes"); no module grows overall.

Not done: helpers whose inline form is a branch diamond (`I32_TRUNC_*`, `F64_COPYSIGN`, ...),
and an inline `I64_EXTEND_I32_U` for the remaining general sites (`i2l; ldc2_w; land`, 5 bytes
against 3 for the call).

## Tests

`NarrowingPeepholeTest` with the generated fixture `narrowing.wat`
(`data/compiler-census/fixtures/gen_narrowing_wat.py`, compiled with wabt 1.0.40): every rewritten
shape for all six operations, both extensions and four constants (including ones that do not fit
in 32 bits), the narrow loads with and without offset, boolean extends, and five near misses.
The differential check compares compiled and interpreted results, including traps, on values
that put loads at the end of memory; the structural check asserts what the analyzer emits. The
KitchenSink approval snapshot changed only in the expected `rotl`/`rotr`/`extend8_s` lines.

## Measurements (static)

`CensusDumpTest` + `census.py`, before (`f00d3bc9`) and after (`17542744`):

| module | func_* bytes before | after | change | `I64_EXTEND_I32_U` calls |
| --- | ---: | ---: | ---: | --- |
| main.go.wasm | 1,671,946 | 1,595,528 | −4.57 % | 16,615 → 585 (as `Integer.toUnsignedLong`) |
| javy-demo.js.javy.wasm | 961,586 | 961,220 | −0.04 % | 610 → 542 |
| quickjs-provider.javy-dynamic.wasm | 1,026,534 | 1,026,272 | −0.03 % | 659 → 591 |
| basic.dotnet.wasm | 1,511,113 | 1,510,972 | −0.01 % | 135 → 122 |
| greet-wasi.rs.wasm, code.wasm | unchanged | | | |

The largest Go method shrank from 32,090 to 29,841 bytes. The number of methods over 8000 bytes
did not change in any module.
