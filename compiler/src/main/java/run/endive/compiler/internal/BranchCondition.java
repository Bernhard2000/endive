package run.endive.compiler.internal;

import java.util.Optional;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;
import run.endive.wasm.types.Value;

/**
 * A condition that a conditional jump can test directly, without first materializing a 0/1
 * {@code i32} through a helper call.
 *
 * <p>Each condition is lowered to an optional {@link Prefix} ({@code lcmp}, {@code fcmpl}, ...)
 * followed by one JVM conditional jump. The jump opcodes come in complementary pairs (EQ/NE,
 * LT/GE, GT/LE, NULL/NONNULL), so a condition can be tested for both outcomes. For floating
 * point, the prefix is chosen so that a NaN operand makes the condition false (the {@code g}
 * variant for LT/LE, the {@code l} variant for GT/GE), which is what Wasm requires. Jumping on the
 * negated outcome then also takes the NaN case correctly.
 */
enum BranchCondition {
    /** {@code x != 0}: the implicit condition of {@code if}, {@code br_if} and {@code select}. */
    I32_NEZ(Prefix.NONE, Opcodes.IFNE),
    // comparisons of an i32 against zero, produced from comparisons with a constant operand
    I32_EQZ(Prefix.NONE, Opcodes.IFEQ),
    I32_LTZ(Prefix.NONE, Opcodes.IFLT),
    I32_GTZ(Prefix.NONE, Opcodes.IFGT),
    I32_LEZ(Prefix.NONE, Opcodes.IFLE),
    I32_GEZ(Prefix.NONE, Opcodes.IFGE),
    I32_EQ(Prefix.NONE, Opcodes.IF_ICMPEQ),
    I32_NE(Prefix.NONE, Opcodes.IF_ICMPNE),
    I32_LT_S(Prefix.NONE, Opcodes.IF_ICMPLT),
    I32_GT_S(Prefix.NONE, Opcodes.IF_ICMPGT),
    I32_LE_S(Prefix.NONE, Opcodes.IF_ICMPLE),
    I32_GE_S(Prefix.NONE, Opcodes.IF_ICMPGE),
    I64_EQZ(Prefix.LCMP_ZERO, Opcodes.IFEQ),
    I64_EQ(Prefix.LCMP, Opcodes.IFEQ),
    I64_NE(Prefix.LCMP, Opcodes.IFNE),
    I64_LT_S(Prefix.LCMP, Opcodes.IFLT),
    I64_GT_S(Prefix.LCMP, Opcodes.IFGT),
    I64_LE_S(Prefix.LCMP, Opcodes.IFLE),
    I64_GE_S(Prefix.LCMP, Opcodes.IFGE),
    I64_LT_U(Prefix.LCMP_UNSIGNED, Opcodes.IFLT),
    I64_GT_U(Prefix.LCMP_UNSIGNED, Opcodes.IFGT),
    I64_LE_U(Prefix.LCMP_UNSIGNED, Opcodes.IFLE),
    I64_GE_U(Prefix.LCMP_UNSIGNED, Opcodes.IFGE),
    F32_EQ(Prefix.FCMPL, Opcodes.IFEQ),
    F32_NE(Prefix.FCMPL, Opcodes.IFNE),
    F32_LT(Prefix.FCMPG, Opcodes.IFLT),
    F32_GT(Prefix.FCMPL, Opcodes.IFGT),
    F32_LE(Prefix.FCMPG, Opcodes.IFLE),
    F32_GE(Prefix.FCMPL, Opcodes.IFGE),
    F64_EQ(Prefix.DCMPL, Opcodes.IFEQ),
    F64_NE(Prefix.DCMPL, Opcodes.IFNE),
    F64_LT(Prefix.DCMPG, Opcodes.IFLT),
    F64_GT(Prefix.DCMPL, Opcodes.IFGT),
    F64_LE(Prefix.DCMPG, Opcodes.IFLE),
    F64_GE(Prefix.DCMPL, Opcodes.IFGE),
    /** {@code ref.is_null} on a reference represented as a JVM object. */
    REF_IS_NULL(Prefix.NONE, Opcodes.IFNULL),
    /** {@code ref.is_null} on a reference represented as an {@code int} ({@code funcref}). */
    INT_REF_IS_NULL(Prefix.REF_NULL_VALUE, Opcodes.IF_ICMPEQ),
    /** {@code ref.eq} where one operand cannot be an {@code i31ref}: reference identity. */
    REF_EQ(Prefix.NONE, Opcodes.IF_ACMPEQ);

    /** What is emitted before the jump to turn the operands into the value it tests. */
    private enum Prefix {
        NONE,
        /** {@code lconst_0; lcmp}: compares an i64 against zero. */
        LCMP_ZERO,
        LCMP,
        /** {@code Long.compareUnsigned(a, b)}, whose sign the jump then tests. */
        LCMP_UNSIGNED,
        FCMPL,
        FCMPG,
        DCMPL,
        DCMPG,
        /** pushes the {@code int} encoding of a null reference. */
        REF_NULL_VALUE
    }

    private static final BranchCondition[] VALUES = values();

    private final Prefix prefix;
    private final int jumpIfTrue;

    BranchCondition(Prefix prefix, int jumpIfTrue) {
        this.prefix = prefix;
        this.jumpIfTrue = jumpIfTrue;
    }

    static BranchCondition fromId(long id) {
        return VALUES[(int) id];
    }

    // The id only lives inside one compilation, as an operand read back by fromId
    @SuppressWarnings("EnumOrdinal")
    long id() {
        return ordinal();
    }

    /**
     * Returns the condition computed by a comparison instruction whose 0/1 result can be fused
     * into the jump consuming it. {@code i32.eqz} is not listed: it is absorbed by negating the
     * jump instead.
     *
     * <p>Unsigned {@code i32} comparisons are deliberately absent. The JVM has no unsigned
     * {@code int} compare, and the usual replacements ({@code Integer.compareUnsigned}, or biasing
     * both operands by {@code MIN_VALUE} before a signed compare) produce the signed compares on
     * biased operands that trigger a C2 miscompilation on JDK 11 to 17 (the {@code
     * fold_compares} bug {@code MemCopyWorkaround} guards {@code I32_GE_U} and {@code I32_LT_U}
     * against). Those comparisons keep their helper call, unless a constant operand allows a
     * rewrite that needs no bias (see {@code WasmAnalyzer.fuseBranchConditions}). The {@code i64}
     * forms are fused through {@code Long.compareUnsigned}: {@code fold_compares} only merges
     * {@code int} compares ({@code IfNode::cmpi_folds}), so they are not exposed.
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
            case I64_LT_U:
                return Optional.of(I64_LT_U);
            case I64_GT_U:
                return Optional.of(I64_GT_U);
            case I64_LE_U:
                return Optional.of(I64_LE_U);
            case I64_GE_U:
                return Optional.of(I64_GE_U);
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
            case REF_EQ_FUSABLE:
                return Optional.of(REF_EQ);
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
            case NONE:
                break;
            case LCMP_ZERO:
                asm.lconst(0);
                asm.lcmp();
                break;
            case LCMP:
                asm.lcmp();
                break;
            case LCMP_UNSIGNED:
                asm.invokestatic("java/lang/Long", "compareUnsigned", "(JJ)I", false);
                break;
            case FCMPL:
                asm.cmpl(Type.FLOAT_TYPE);
                break;
            case FCMPG:
                asm.cmpg(Type.FLOAT_TYPE);
                break;
            case DCMPL:
                asm.cmpl(Type.DOUBLE_TYPE);
                break;
            case DCMPG:
                asm.cmpg(Type.DOUBLE_TYPE);
                break;
            case REF_NULL_VALUE:
                asm.iconst(Value.REF_NULL_VALUE);
                break;
        }
        asm.visitJumpInsn(whenTrue ? jumpIfTrue : negate(jumpIfTrue), target);
    }

    // IFEQ..IFLE and IF_ICMPEQ..IF_ICMPLE are laid out as EQ, NE, LT, GE, GT, LE, IF_ACMPEQ is
    // followed by IF_ACMPNE, and IFNULL by IFNONNULL, so each opcode's complement differs from it
    // only in the lowest bit of the offset.
    static int negate(int jump) {
        int base;
        if (jump >= Opcodes.IFNULL) {
            base = Opcodes.IFNULL;
        } else if (jump >= Opcodes.IF_ACMPEQ) {
            base = Opcodes.IF_ACMPEQ;
        } else if (jump >= Opcodes.IF_ICMPEQ) {
            base = Opcodes.IF_ICMPEQ;
        } else {
            base = Opcodes.IFEQ;
        }
        return base + ((jump - base) ^ 1);
    }

    int jumpIfTrue() {
        return jumpIfTrue;
    }
}
