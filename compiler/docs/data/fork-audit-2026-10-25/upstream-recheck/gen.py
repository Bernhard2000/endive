# Generates a differential-test module for comparison fusion and narrowing.
import itertools
I32_CMP = ["eq","ne","lt_s","lt_u","gt_s","gt_u","le_s","le_u","ge_s","ge_u"]
I64_CMP = I32_CMP
F_CMP = ["eq","ne","lt","gt","le","ge"]
CONSTS = [0,1,-1,2,255,256,0x7fffffff,-0x80000000,-0x7fffffff,-2,0x40000000,65536]
funcs = []
n = 0
def add(prefix, params, body):
    global n
    funcs.append(f'(func (export "{prefix}_{n}") {params} (result i32) {body})')
    n += 1
def consumers(cond):
    # each consumer turns the i32 condition into an i32 result 7/9 (or the raw value)
    return [
        f"(if (result i32) {cond} (then (i32.const 7)) (else (i32.const 9)))",
        f"(if (result i32) (i32.eqz {cond}) (then (i32.const 7)) (else (i32.const 9)))",
        f"(block (result i32) (drop (br_if 0 (i32.const 7) {cond})) (i32.const 9))",
        f"(block (result i32) (drop (br_if 0 (i32.const 7) (i32.eqz (i32.eqz {cond})))) (i32.const 9))",
        f"(select (i32.const 7) (i32.const 9) {cond})",
        f"(select (i32.const 7) (i32.const 9) (i64.eqz (i64.extend_i32_u {cond})))",
        f"(i32.wrap_i64 (i64.extend_i32_u {cond}))",
        cond,
        f"(i32.eqz {cond})",
    ]
for op in I32_CMP:
    for c in CONSTS:
        for body in consumers(f"(i32.{op} (local.get 0) (i32.const {c}))"):
            add("i", "(param i32)", body)
        for body in consumers(f"(i32.{op} (i32.const {c}) (local.get 0))")[:3]:
            add("i", "(param i32)", body)
    for body in consumers(f"(i32.{op} (local.get 0) (local.get 1))"):
        add("ii", "(param i32 i32)", body)
for op in I64_CMP:
    for body in consumers(f"(i64.{op} (local.get 0) (local.get 1))"):
        add("ll", "(param i64 i64)", body)
    for body in consumers(f"(i64.eqz (local.get 0))")[:5]:
        add("ll", "(param i64 i64)", body)
for t in ["f32","f64"]:
    for op in F_CMP:
        for body in consumers(f"({t}.{op} (local.get 0) (local.get 1))"):
            add("ff" if t=="f32" else "dd", f"(param {t} {t})", body)
# narrowing
for op in ["add","sub","mul","and","or","xor"]:
    for ext in ["u","s"]:
        for c in [0,1,-1,0x7fffffff,0x100000001,-0x100000000,0xffffffff]:
            add("i", "(param i32)", f"(i32.wrap_i64 (i64.{op} (i64.extend_i32_{ext} (local.get 0)) (i64.const {c})))")
            add("i", "(param i32)", f"(i32.wrap_i64 (i64.{op} (i64.const {c}) (i64.extend_i32_{ext} (local.get 0))))")
for ld in ["load8_s","load8_u","load16_s","load16_u","load32_s","load32_u"]:
    add("i", "(param i32)", f"(i32.wrap_i64 (i64.{ld} offset=3 (local.get 0)))")
print("(module (memory 1) (data (i32.const 0) \"\\80\\ff\\7f\\01\\fe\\81\\00\\80\\ff\\ff\\ff\\ff\")")
print("\n".join(funcs))
print(")")
