package run.endive.compiler.internal;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites {@code i64} computations whose result is immediately narrowed back to {@code i32}
 * into the equivalent {@code i32} computation, and drops zero-extensions of values that are
 * already 0 or 1.
 *
 * <p>Compilers that model pointers or booleans as {@code i64} (Go in particular) emit these
 * shapes in large numbers. {@code i64.extend_i32_u} is a helper call, so the original sequences
 * cost a call and several bytes per site. The rewrites are exact:
 *
 * <ul>
 *   <li>{@code (i32.wrap_i64 (i64.<op> (i64.extend_i32_{u,s} x) (i64.const c)))} is {@code
 *       (i32.<op> x (i32.const (int) c))} for {@code add}, {@code sub}, {@code mul}, {@code
 *       and}, {@code or} and {@code xor}: the low 32 bits of their result depend only on the low
 *       32 bits of the operands;
 *   <li>{@code (i32.wrap_i64 (i64.load<N>_{u,s} addr))} is {@code (i32.load<N>_{u,s} addr)} for
 *       {@code N} of 8, 16 and 32 bits: both read the same bytes, so they also trap alike. A full
 *       {@code i64.load} is not rewritten, because it traps for addresses where {@code i32.load}
 *       does not;
 *   <li>{@code i64.extend_i32_u} of the 0/1 result of a comparison is {@code i64.extend_i32_s},
 *       a plain {@code i2l}.
 * </ul>
 *
 * <p>Only adjacent instructions are rewritten. Jump targets are explicit {@link
 * CompilerOpCode#LABEL} instructions, so adjacency guarantees that no control flow enters inside
 * a rewritten sequence.
 */
final class NarrowingPeephole {

    private static final Map<CompilerOpCode, CompilerOpCode> NARROW_BINARY =
            new EnumMap<>(CompilerOpCode.class);
    private static final Map<CompilerOpCode, CompilerOpCode> NARROW_LOAD =
            new EnumMap<>(CompilerOpCode.class);
    private static final Set<CompilerOpCode> BOOLEAN_PRODUCERS =
            EnumSet.noneOf(CompilerOpCode.class);

    static {
        NARROW_BINARY.put(CompilerOpCode.I64_ADD, CompilerOpCode.I32_ADD);
        NARROW_BINARY.put(CompilerOpCode.I64_SUB, CompilerOpCode.I32_SUB);
        NARROW_BINARY.put(CompilerOpCode.I64_MUL, CompilerOpCode.I32_MUL);
        NARROW_BINARY.put(CompilerOpCode.I64_AND, CompilerOpCode.I32_AND);
        NARROW_BINARY.put(CompilerOpCode.I64_OR, CompilerOpCode.I32_OR);
        NARROW_BINARY.put(CompilerOpCode.I64_XOR, CompilerOpCode.I32_XOR);

        NARROW_LOAD.put(CompilerOpCode.I64_LOAD8_S, CompilerOpCode.I32_LOAD8_S);
        NARROW_LOAD.put(CompilerOpCode.I64_LOAD8_U, CompilerOpCode.I32_LOAD8_U);
        NARROW_LOAD.put(CompilerOpCode.I64_LOAD16_S, CompilerOpCode.I32_LOAD16_S);
        NARROW_LOAD.put(CompilerOpCode.I64_LOAD16_U, CompilerOpCode.I32_LOAD16_U);
        NARROW_LOAD.put(CompilerOpCode.I64_LOAD32_S, CompilerOpCode.I32_LOAD);
        NARROW_LOAD.put(CompilerOpCode.I64_LOAD32_U, CompilerOpCode.I32_LOAD);

        for (var prefix : new String[] {"I32_", "I64_"}) {
            for (var op :
                    new String[] {
                        "EQZ", "EQ", "NE", "LT_S", "LT_U", "GT_S", "GT_U", "LE_S", "LE_U", "GE_S",
                        "GE_U"
                    }) {
                BOOLEAN_PRODUCERS.add(CompilerOpCode.valueOf(prefix + op));
            }
        }
        for (var prefix : new String[] {"F32_", "F64_"}) {
            for (var op : new String[] {"EQ", "NE", "LT", "GT", "LE", "GE"}) {
                BOOLEAN_PRODUCERS.add(CompilerOpCode.valueOf(prefix + op));
            }
        }
        BOOLEAN_PRODUCERS.add(CompilerOpCode.REF_IS_NULL);
    }

    private NarrowingPeephole() {}

    static List<CompilerInstruction> apply(List<CompilerInstruction> in) {
        List<CompilerInstruction> out = new ArrayList<>(in.size());
        for (var ins : in) {
            switch (ins.opcode()) {
                case I32_WRAP_I64:
                    if (narrowBinary(out) || narrowLoad(out)) {
                        continue;
                    }
                    break;
                case I64_EXTEND_I32_U:
                    if (!out.isEmpty() && BOOLEAN_PRODUCERS.contains(last(out).opcode())) {
                        out.add(new CompilerInstruction(CompilerOpCode.I64_EXTEND_I32_S));
                        continue;
                    }
                    break;
                default:
                    break;
            }
            out.add(ins);
        }
        return out;
    }

    // ... extend; i64.const c; op  ->  ... i32.const (int) c; op32
    private static boolean narrowBinary(List<CompilerInstruction> out) {
        int n = out.size();
        if (n < 3) {
            return false;
        }
        var narrowed = NARROW_BINARY.get(out.get(n - 1).opcode());
        var constant = out.get(n - 2);
        var extend = out.get(n - 3).opcode();
        if (narrowed == null
                || constant.opcode() != CompilerOpCode.I64_CONST
                || (extend != CompilerOpCode.I64_EXTEND_I32_U
                        && extend != CompilerOpCode.I64_EXTEND_I32_S)) {
            return false;
        }
        out.subList(n - 3, n).clear();
        out.add(new CompilerInstruction(CompilerOpCode.I32_CONST, (int) constant.operand(0)));
        out.add(new CompilerInstruction(narrowed));
        return true;
    }

    private static boolean narrowLoad(List<CompilerInstruction> out) {
        if (out.isEmpty()) {
            return false;
        }
        var load = last(out);
        var narrowed = NARROW_LOAD.get(load.opcode());
        if (narrowed == null) {
            return false;
        }
        out.set(out.size() - 1, new CompilerInstruction(narrowed, load.operands().toArray()));
        return true;
    }

    private static CompilerInstruction last(List<CompilerInstruction> list) {
        return list.get(list.size() - 1);
    }
}
