#!/bin/bash
# usage: calls.sh dir  -> counts of comparison-helper invokestatics
for c in "$1"/*.class; do javap -c -p "$c" 2>/dev/null; done | grep -oE "invokestatic .*// Method [^ ]*(OpcodeImpl\.(I32|I64|F32|F64)_(EQZ|EQ|NE|LT_S|LT_U|GT_S|GT_U|LE_S|LE_U|GE_S|GE_U|LT|GT|LE|GE)|i32_(ge|lt)_u|isGcRefNull|isRefNull|compareUnsigned)" | sed -E 's/.*Method //' | sort | uniq -c | sort -rn
