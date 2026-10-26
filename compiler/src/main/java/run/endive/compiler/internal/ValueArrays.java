package run.endive.compiler.internal;

import static org.objectweb.asm.Type.LONG_TYPE;
import static org.objectweb.asm.commons.InstructionAdapter.OBJECT_TYPE;
import static run.endive.compiler.internal.CompilerUtil.asmType;
import static run.endive.compiler.internal.CompilerUtil.emitJvmToLong;
import static run.endive.compiler.internal.CompilerUtil.slotCount;

import java.util.List;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;
import run.endive.runtime.CallResult;
import run.endive.wasm.types.ValType;

/**
 * Builds the arrays that carry Wasm values where a JVM signature cannot: boxed arguments, host
 * and interpreter calls, multi-value results, struct fields and exception payloads. Numbers go
 * into a {@code long[]} and JVM object references into an {@code Object[]}, both indexed by the
 * value's position. The values are read from consecutive JVM locals; {@link #spill} moves them
 * there from the operand stack. The {@code from} methods instead convert between these array
 * forms, reading an array or a {@link CallResult} from a single local.
 */
final class ValueArrays {

    /** What a {@code long[]} holds at the index of a JVM object reference. */
    enum RefEntry {
        /** Nothing is stored; the element keeps its default 0. */
        SKIP,
        /** {@code 0L} is stored. */
        ZERO,
        /** The reference is loaded and dropped, then {@code 0L} is stored. */
        LOAD_AND_ZERO
    }

    private static final String CALL_RESULT = Type.getInternalName(CallResult.class);

    private ValueArrays() {}

    /** The JVM slots that values of these types take. */
    static int slots(List<ValType> types) {
        return types.stream().mapToInt(CompilerUtil::slotCount).sum();
    }

    /**
     * Stores values from the operand stack, the last one on top, into the locals starting at
     * {@code firstSlot}, in their order.
     */
    static void spill(InstructionAdapter asm, List<ValType> types, int firstSlot) {
        int slot = firstSlot + slots(types);
        for (int i = types.size() - 1; i >= 0; i--) {
            ValType type = types.get(i);
            slot -= slotCount(type);
            asm.store(slot, asmType(type));
        }
    }

    /** Pushes a {@code long[]} of the values in the locals starting at {@code firstSlot}. */
    static void longArray(
            InstructionAdapter asm, List<ValType> types, int firstSlot, RefEntry refEntry) {
        asm.iconst(types.size());
        asm.newarray(LONG_TYPE);
        int slot = firstSlot;
        for (int i = 0; i < types.size(); i++) {
            ValType type = types.get(i);
            if (!type.isObjectRef()) {
                asm.dup();
                asm.iconst(i);
                asm.load(slot, asmType(type));
                emitJvmToLong(asm, type);
                asm.astore(LONG_TYPE);
            } else if (refEntry == RefEntry.ZERO) {
                asm.dup();
                asm.iconst(i);
                asm.lconst(0L);
                asm.astore(LONG_TYPE);
            } else if (refEntry == RefEntry.LOAD_AND_ZERO) {
                asm.dup();
                asm.iconst(i);
                asm.load(slot, asmType(type));
                asm.visitInsn(Opcodes.POP);
                asm.lconst(0L);
                asm.astore(LONG_TYPE);
            }
            slot += slotCount(type);
        }
    }

    /**
     * Pushes an {@code Object[]} holding the JVM object references among the values in the
     * locals starting at {@code firstSlot}, or {@code null} if there are none.
     */
    static void refArrayOrNull(InstructionAdapter asm, List<ValType> types, int firstSlot) {
        if (types.stream().noneMatch(ValType::isObjectRef)) {
            asm.aconst(null);
            return;
        }
        asm.iconst(types.size());
        asm.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        int slot = firstSlot;
        for (int i = 0; i < types.size(); i++) {
            ValType type = types.get(i);
            if (type.isObjectRef()) {
                asm.dup();
                asm.iconst(i);
                asm.load(slot, asmType(type));
                asm.astore(OBJECT_TYPE);
            }
            slot += slotCount(type);
        }
    }

    /**
     * Pushes an {@code Object[]} of all values in the locals starting at {@code firstSlot}:
     * numbers boxed as {@code Long}, references as they are.
     */
    static void objectArray(InstructionAdapter asm, List<ValType> types, int firstSlot) {
        asm.iconst(types.size());
        asm.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        int slot = firstSlot;
        for (int i = 0; i < types.size(); i++) {
            ValType type = types.get(i);
            asm.dup();
            asm.iconst(i);
            asm.load(slot, asmType(type));
            if (!type.isObjectRef()) {
                emitJvmToLong(asm, type);
                asm.invokestatic("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            }
            asm.astore(OBJECT_TYPE);
            slot += slotCount(type);
        }
    }

    /**
     * Pushes a {@code long[]} of the numbers in the {@code Object[]} in local {@code arraySlot},
     * which holds them boxed as {@code Long}.
     */
    static void longArrayFromObjectArray(
            InstructionAdapter asm, List<ValType> types, int arraySlot, RefEntry refEntry) {
        if (refEntry == RefEntry.LOAD_AND_ZERO) {
            throw new IllegalArgumentException(
                    "A boxed reference has nothing to load: " + refEntry);
        }
        asm.iconst(types.size());
        asm.newarray(LONG_TYPE);
        for (int i = 0; i < types.size(); i++) {
            if (!types.get(i).isObjectRef()) {
                asm.dup();
                asm.iconst(i);
                asm.load(arraySlot, OBJECT_TYPE);
                asm.iconst(i);
                asm.aload(OBJECT_TYPE);
                asm.checkcast(Type.getType(Long.class));
                asm.invokevirtual("java/lang/Long", "longValue", "()J", false);
                asm.astore(LONG_TYPE);
            } else if (refEntry == RefEntry.ZERO) {
                asm.dup();
                asm.iconst(i);
                asm.lconst(0L);
                asm.astore(LONG_TYPE);
            }
        }
    }

    /**
     * Pushes an {@code Object[]} of the JVM object references in the {@code Object[]} in local
     * {@code arraySlot}; the other elements stay {@code null}.
     */
    static void refArrayFromObjectArray(
            InstructionAdapter asm, List<ValType> types, int arraySlot) {
        asm.iconst(types.size());
        asm.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        for (int i = 0; i < types.size(); i++) {
            if (types.get(i).isObjectRef()) {
                asm.dup();
                asm.iconst(i);
                asm.load(arraySlot, OBJECT_TYPE);
                asm.iconst(i);
                asm.aload(OBJECT_TYPE);
                asm.astore(OBJECT_TYPE);
            }
        }
    }

    /**
     * Pushes an {@code Object[]} of the values in the {@link CallResult} in local
     * {@code callResultSlot}: numbers boxed as {@code Long}, references as they are. Both of its
     * arrays are read with one running index, which a {@code v128} advances by two.
     */
    static void objectArrayFromCallResult(
            InstructionAdapter asm, List<ValType> types, int callResultSlot) {
        asm.iconst(types.size());
        asm.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        int index = 0;
        for (int i = 0; i < types.size(); i++) {
            asm.dup();
            asm.iconst(i);
            ValType type = types.get(i);
            asm.load(callResultSlot, OBJECT_TYPE);
            asm.iconst(index);
            if (type.isObjectRef()) {
                asm.invokevirtual(CALL_RESULT, "refResult", "(I)Ljava/lang/Object;", false);
            } else {
                asm.invokevirtual(CALL_RESULT, "longResult", "(I)J", false);
                asm.invokestatic("java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
            }
            index += type.equals(ValType.V128) ? 2 : 1;
            asm.astore(OBJECT_TYPE);
        }
    }
}
