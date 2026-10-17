#!/usr/bin/env python3
"""Generates wasm-corpus/src/main/resources/wat/bool_values.wat (CompilerOptionsTest).
Compile with wabt 1.0.40: wat2wasm bool_values.wat -o ../compiled/bool_values.wat.wasm"""
out = ["(module", "  ;; Comparison results used as values (stored, returned, combined), for CompilerOptionsTest."]
for t in ("i32", "i64"):
    for op in ("eqz", "eq", "ne", "lt_u", "gt_u", "le_u", "ge_u"):
        args = "local.get 0" if op == "eqz" else "local.get 0 local.get 1"
        out.append(f'  (func (export "{t}_{op}_value") (param {t} {t}) (result i32) {args} {t}.{op})')
        out.append(f'  (func (export "{t}_{op}_local") (param {t} {t}) (result i32) (local i32) {args} {t}.{op} local.set 2 local.get 2 i32.const 3 i32.mul)')
        out.append(f'  (func (export "{t}_{op}_if") (param {t} {t}) (result i32) (if (result i32) ({t}.{op} {args.replace("local.get", "(local.get").replace(" (", ") (") + ")"}) (then (i32.const 7)) (else (i32.const 9))))')
        out.append(f'  (func (export "{t}_{op}_sum") (param {t} {t}) (result i32) {args} {t}.{op} {args} {t}.{op} i32.add)')
out.append(")")
print("\n".join(out))
