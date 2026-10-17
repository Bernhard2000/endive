#!/usr/bin/env python3
"""Static census of classes Endive generated (see CensusDumpTest.java).

    census.py DIR [DIR ...]            one summary per module directory
    census.py --compare BASE NEW ...   the same for two or more variants side by side,
                                       where BASE/NEW contain one directory per module
    --json OUT                         also write the full numbers as JSON
    --calls PATTERN                    only count callees whose "Owner.name" matches (regex)

Per module it reports the total `Code` length of the `func_*` methods, how many exceed
HotSpot's 8000-byte HugeMethodLimit and the 64 KiB JVM limit, and every `invokestatic`
target with its number of call sites. The class files are parsed here; no javap.
"""
import argparse
import json
import re
import struct
import sys
from collections import Counter
from pathlib import Path

# operand bytes of fixed-length opcodes; tableswitch, lookupswitch and wide are special
_LEN = [0] * 256
for op in range(0x10, 0x12):  # bipush, sipush
    _LEN[op] = op - 0x0F
_LEN[0x12] = 1  # ldc
_LEN[0x13] = _LEN[0x14] = 2  # ldc_w, ldc2_w
for op in range(0x15, 0x1A):  # iload..aload
    _LEN[op] = 1
for op in range(0x36, 0x3B):  # istore..astore
    _LEN[op] = 1
_LEN[0x84] = 2  # iinc
for op in range(0x99, 0xA9):  # if*, goto, jsr
    _LEN[op] = 2
_LEN[0xA9] = 1  # ret
for op in range(0xB2, 0xB9):  # get/put field/static, invokevirtual/special/static
    _LEN[op] = 2
_LEN[0xB9] = 4  # invokeinterface
_LEN[0xBA] = 4  # invokedynamic
_LEN[0xBB] = 2  # new
_LEN[0xBC] = 1  # newarray
_LEN[0xBD] = 2  # anewarray
_LEN[0xC0] = _LEN[0xC1] = 2  # checkcast, instanceof
_LEN[0xC5] = 3  # multianewarray
_LEN[0xC6] = _LEN[0xC7] = 2  # ifnull, ifnonnull
_LEN[0xC8] = _LEN[0xC9] = 4  # goto_w, jsr_w


def parse_class(data):
    pos = 8
    def u1():
        nonlocal pos
        pos += 1
        return data[pos - 1]
    def u2():
        nonlocal pos
        pos += 2
        return struct.unpack_from(">H", data, pos - 2)[0]
    def u4():
        nonlocal pos
        pos += 4
        return struct.unpack_from(">I", data, pos - 4)[0]
    utf8, refs, classes, nat = {}, {}, {}, {}
    count = u2()
    i = 1
    while i < count:
        tag = u1()
        if tag == 1:
            n = u2()
            utf8[i] = data[pos:pos + n].decode("utf-8", "replace")
            pos += n
        elif tag in (3, 4):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            i += 1
        elif tag == 7:
            classes[i] = u2()
        elif tag in (8, 16, 19, 20):
            pos += 2
        elif tag in (9, 10, 11):
            refs[i] = (u2(), u2())
        elif tag == 12:
            nat[i] = (u2(), u2())
        elif tag in (17, 18):
            pos += 4
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f"constant pool tag {tag}")
        i += 1

    def ref_name(idx):
        cls, nt = refs[idx]
        owner = utf8[classes[cls]].rsplit("/", 1)[-1]
        name, desc = nat[nt]
        return f"{owner}.{utf8[name]}"

    pos += 6
    n = u2()
    pos += 2 * n
    for _ in range(u2()):  # fields
        pos += 6
        for _ in range(u2()):
            pos += 2
            n = u4()
            pos += n
    methods = []
    for _ in range(u2()):
        pos += 2
        name = utf8[u2()]
        desc = utf8[u2()]
        for _ in range(u2()):
            attr = utf8[u2()]
            size = u4()
            start = pos
            if attr == "Code":
                pos += 4
                length = u4()
                code = data[pos:pos + length]
                calls = Counter()
                pc = 0
                while pc < length:
                    op = code[pc]
                    if op == 0xB8:
                        calls[ref_name(struct.unpack_from(">H", code, pc + 1)[0])] += 1
                    if op in (0xAA, 0xAB):  # tableswitch, lookupswitch
                        p = (pc + 4) & ~3
                        if op == 0xAA:
                            lo, hi = struct.unpack_from(">ii", code, p + 4)
                            pc = p + 12 + 4 * (hi - lo + 1)
                        else:
                            n = struct.unpack_from(">i", code, p + 4)[0]
                            pc = p + 8 + 8 * n
                        continue
                    if op == 0xC4:  # wide
                        pc += 6 if code[pc + 1] == 0x84 else 4
                        continue
                    pc += 1 + _LEN[op]
                methods.append({"name": name, "descriptor": desc, "code_length": length,
                                "calls": calls})
            pos = start + size
    return methods


def module_census(directory, calls_filter=None):
    methods = []
    for path in sorted(Path(directory).glob("*.class")):
        methods.extend(parse_class(path.read_bytes()))
    func = [m for m in methods if m["name"].startswith("func_")]
    calls = Counter()
    for m in methods:
        for k, v in m["calls"].items():
            if calls_filter is None or calls_filter.search(k):
                calls[k] += v
    return {
        "func_bytes": sum(m["code_length"] for m in func),
        "all_bytes": sum(m["code_length"] for m in methods),
        "func_methods": len(func),
        "over_8000": sum(1 for m in func if m["code_length"] > 8000),
        "max_func": max((m["code_length"] for m in func), default=0),
        "calls": dict(calls.most_common()),
        "func_sizes": {m["name"]: m["code_length"] for m in func},
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dirs", nargs="+")
    ap.add_argument("--compare", action="store_true")
    ap.add_argument("--json")
    ap.add_argument("--calls", help="regex on Owner.name")
    ap.add_argument("--top", type=int, default=25)
    args = ap.parse_args()
    flt = re.compile(args.calls) if args.calls else None

    if not args.compare:
        result = {}
        for d in args.dirs:
            c = module_census(d, flt)
            result[Path(d).name] = c
            print(f"== {Path(d).name}: func={c['func_bytes']} methods={c['func_methods']} "
                  f">8000={c['over_8000']} max={c['max_func']}")
            for k, v in list(c["calls"].items())[:args.top]:
                print(f"  {v:8d}  {k}")
    else:
        variants = args.dirs
        modules = sorted(p.name for p in Path(variants[0]).iterdir() if p.is_dir())
        result = {}
        for mod in modules:
            per = {Path(v).name: module_census(Path(v) / mod, flt) for v in variants}
            result[mod] = per
            names = list(per)
            base = per[names[0]]
            print(f"== {mod}")
            for n in names:
                c = per[n]
                rel = (c["func_bytes"] - base["func_bytes"]) / base["func_bytes"] * 100 \
                    if base["func_bytes"] else 0
                print(f"  {n:24s} func={c['func_bytes']:>10} ({rel:+.2f}%) "
                      f">8000={c['over_8000']:>3} max={c['max_func']:>6}")
            keys = Counter()
            for c in per.values():
                keys.update(c["calls"])
            for k, _ in keys.most_common(args.top):
                print("    " + " ".join(f"{per[n]['calls'].get(k, 0):>8d}" for n in names)
                      + f"  {k}")
    if args.json:
        Path(args.json).write_text(json.dumps(result, indent=1, sort_keys=True) + "\n")


if __name__ == "__main__":
    sys.exit(main())
