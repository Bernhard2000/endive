package run.endive.compiler.internal;

import java.util.Optional;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;

/**
 * A condition that a conditional jump can test directly, without first materializing a 0/1
 * {@code i32} through a helper call.
 *
 * <p>Each condition is lowered to an optional compare prefix ({@code lcmp}, {@code fcmpl}, ...)
 * followed by one JVM conditional jump. The jump opcodes come in complementary pairs (EQ/NE,
 * LT/GE, GT/LE), so a condition can be tested for both outcomes. For floating point, the prefix
 * is chosen so that a NaN operand makes the condition false (the {@code g} variant for LT/LE, the
 * {@code l} variant for GT/GE), which is what Wasm requires. Jumping on the negated outcome then
 * also takes the NaN case correctly.
 */
enum BranchCondition {
    /** {@code x != 0}: the implicit condition of {@code if}, {@code br_if} and {@code select}. */
    I32_NEZ(Opcodes.NOP, Opcodes.IFNE),
    I32_EQ(Opcodes.NOP, Opcodes.IF_ICMPEQ),
    I32_NE(Opcodes.NOP, Opcodes.IF_ICMPNE),
    I32_LT_S(Opcodes.NOP, Opcodes.IF_ICMPLT),
    I32_GT_S(Opcodes.NOP, Opcodes.IF_ICMPGT),
    I32_LE_S(Opcodes.NOP, Opcodes.IF_ICMPLE),
    I32_GE_S(Opcodes.NOP, Opcodes.IF_ICMPGE),
    I64_EQZ(Opcodes.LCONST_0, Opcodes.IFEQ),
    I64_EQ(Opcodes.LCMP, Opcodes.IFEQ),
    I64_NE(Opcodes.LCMP, Opcodes.IFNE),
    I64_LT_S(Opcodes.LCMP, Opcodes.IFLT),
    I64_GT_S(Opcodes.LCMP, Opcodes.IFGT),
    I64_LE_S(Opcodes.LCMP, Opcodes.IFLE),
    I64_GE_S(Opcodes.LCMP, Opcodes.IFGE),
    F32_EQ(Opcodes.FCMPL, Opcodes.IFEQ),
    F32_NE(Opcodes.FCMPL, Opcodes.IFNE),
    F32_LT(Opcodes.FCMPG, Opcodes.IFLT),
    F32_GT(Opcodes.FCMPL, Opcodes.IFGT),
    F32_LE(Opcodes.FCMPG, Opcodes.IFLE),
    F32_GE(Opcodes.FCMPL, Opcodes.IFGE),
    F64_EQ(Opcodes.DCMPL, Opcodes.IFEQ),
    F64_NE(Opcodes.DCMPL, Opcodes.IFNE),
    F64_LT(Opcodes.DCMPG, Opcodes.IFLT),
    F64_GT(Opcodes.DCMPL, Opcodes.IFGT),
    F64_LE(Opcodes.DCMPG, Opcodes.IFLE),
    F64_GE(Opcodes.DCMPL, Opcodes.IFGE);

    private static final BranchCondition[] VALUES = values();

    private final int prefix;
    private final int jumpIfTrue;

    BranchCondition(int prefix, int jumpIfTrue) {
        this.prefix = prefix;
        this.jumpIfTrue = jumpIfTrue;
    }

    static BranchCondition fromId(long id) {
        return VALUES[(int) id];
    }

    long id() {
        return ordinal();
    }

    /**
     * Returns the condition computed by a comparison instruction whose 0/1 result can be fused
     * into the jump consuming it. Unsigned comparisons are not fused: without a JVM unsigned
     * compare they would still need a helper call, so fusing saves nothing. {@code i32.eqz} is
     * not listed: it is absorbed by negating the jump instead.
     */
    static Optional<BranchCondition> ofComparison(CompilerOpCode opcode) {
        switch (opcode) {
            case I32_EQ:
                return Optional.of(I32_EQ);
            case I32_NE:
                return Optional.of(I32_NE);
            case I32_LT_S:
                return Optional.of(I32_LT_S);
            case I32_GT_S:
                return Optional.of(I32_GT_S);
            case I32_LE_S:
                return Optional.of(I32_LE_S);
            case I32_GE_S:
                return Optional.of(I32_GE_S);
            case I64_EQZ:
                return Optional.of(I64_EQZ);
            case I64_EQ:
                return Optional.of(I64_EQ);
            case I64_NE:
                return Optional.of(I64_NE);
            case I64_LT_S:
                return Optional.of(I64_LT_S);
            case I64_GT_S:
                return Optional.of(I64_GT_S);
            case I64_LE_S:
                return Optional.of(I64_LE_S);
            case I64_GE_S:
                return Optional.of(I64_GE_S);
            case F32_EQ:
                return Optional.of(F32_EQ);
            case F32_NE:
                return Optional.of(F32_NE);
            case F32_LT:
                return Optional.of(F32_LT);
            case F32_GT:
                return Optional.of(F32_GT);
            case F32_LE:
                return Optional.of(F32_LE);
            case F32_GE:
                return Optional.of(F32_GE);
            case F64_EQ:
                return Optional.of(F64_EQ);
            case F64_NE:
                return Optional.of(F64_NE);
            case F64_LT:
                return Optional.of(F64_LT);
            case F64_GT:
                return Optional.of(F64_GT);
            case F64_LE:
                return Optional.of(F64_LE);
            case F64_GE:
                return Optional.of(F64_GE);
            default:
                return Optional.empty();
        }
    }

    /**
     * Emits a jump to {@code target} taken when this condition evaluates to {@code whenTrue}. The
     * operands of the condition are consumed from the stack on both paths.
     */
    void emitJump(InstructionAdapter asm, Label target, boolean whenTrue) {
        switch (prefix) {
            case Opcodes.NOP:
                break;
            case Opcodes.LCONST_0:
                asm.lconst(0);
                asm.lcmp();
                break;
            case Opcodes.LCMP:
                asm.lcmp();
                break;
            case Opcodes.FCMPL:
                asm.cmpl(Type.FLOAT_TYPE);
                break;
            case Opcodes.FCMPG:
                asm.cmpg(Type.FLOAT_TYPE);
                break;
            case Opcodes.DCMPL:
                asm.cmpl(Type.DOUBLE_TYPE);
                break;
            case Opcodes.DCMPG:
                asm.cmpg(Type.DOUBLE_TYPE);
                break;
            default:
                throw new IllegalStateException("Unexpected prefix: " + prefix);
        }
        asm.visitJumpInsn(whenTrue ? jumpIfTrue : negate(jumpIfTrue), target);
    }

    // IFEQ..IFLE and IF_ICMPEQ..IF_ICMPLE are laid out as EQ, NE, LT, GE, GT, LE,
    // so each opcode's complement differs from it only in the lowest bit of the offset.
    private static int negate(int jump) {
        int base = jump >= Opcodes.IF_ICMPEQ ? Opcodes.IF_ICMPEQ : Opcodes.IFEQ;
        return base + ((jump - base) ^ 1);
    }
}
