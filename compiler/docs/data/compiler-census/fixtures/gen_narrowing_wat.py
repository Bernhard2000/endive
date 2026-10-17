#!/usr/bin/env python3
"""Generates wasm-corpus/src/main/resources/wat/narrowing.wat (NarrowingPeephole fixture).

Every export is one shape the peephole rewrites, or a near miss it must leave alone.
Compile with wabt 1.0.40: wat2wasm narrowing.wat -o ../compiled/narrowing.wat.wasm"""
OPS = ["add", "sub", "mul", "and", "or", "xor"]
CONSTS = [("5", "5"), ("m3", "-3"), ("big", "0x100000007"), ("min", "0x8000000000000001")]
out = ["(module", "  (memory (export \"memory\") 1)",
       "  (data (i32.const 0) \"\\81\\82\\83\\84\\85\\86\\87\\88\\f9\\fa\\fb\\fc\\fd\\fe\\ff\\7f\")"]
for ext in ("u", "s"):
    for op in OPS:
        for cname, c in CONSTS:
            out.append(f"  (func (export \"wrap_{ext}_{op}_{cname}\") (param i32) (result i32)\n"
                       f"    local.get 0 i64.extend_i32_{ext} i64.const {c} i64.{op} i32.wrap_i64)")
for load in ("load8_u", "load8_s", "load16_u", "load16_s", "load32_u", "load32_s"):
    out.append(f"  (func (export \"wrap_{load}\") (param i32) (result i32)\n"
               f"    local.get 0 i64.{load} i32.wrap_i64)")
    out.append(f"  (func (export \"wrap_{load}_off\") (param i32) (result i32)\n"
               f"    local.get 0 i64.{load} offset=3 i32.wrap_i64)")
for name, body, ptype in (
        ("ext_lt_u", "local.get 0 local.get 1 i32.lt_u", "i32"),
        ("ext_eqz", "local.get 0 i32.eqz", "i32"),
        ("ext_i64_ne", "local.get 0 i64.extend_i32_s local.get 1 i64.extend_i32_s i64.ne", "i32"),
        ("ext_f64_lt", "local.get 0 f64.convert_i32_s local.get 1 f64.convert_i32_u f64.lt", "i32")):
    out.append(f"  (func (export \"{name}\") (param {ptype} {ptype}) (result i64)\n"
               f"    {body} i64.extend_i32_u)")
# near misses: no wrap, a non-constant operand, a shift, a full i64.load
out.append("  (func (export \"keep_no_wrap\") (param i32) (result i64)\n"
           "    local.get 0 i64.extend_i32_u i64.const 0x100000007 i64.add)")
out.append("  (func (export \"keep_var\") (param i32 i32) (result i32)\n"
           "    local.get 0 i64.extend_i32_u local.get 1 i64.extend_i32_s i64.add i32.wrap_i64)")
out.append("  (func (export \"keep_shl\") (param i32) (result i32)\n"
           "    local.get 0 i64.extend_i32_u i64.const 33 i64.shl i32.wrap_i64)")
out.append("  (func (export \"keep_load64\") (param i32) (result i32)\n"
           "    local.get 0 i64.load i32.wrap_i64)")
out.append("  (func (export \"keep_ext_value\") (param i32) (result i64)\n"
           "    local.get 0 i64.extend_i32_u)")
out.append(")")
print("\n".join(out))
