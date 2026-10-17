package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.Value;

/**
 * Checks the fusion of comparisons into the jumps and selects consuming them. Every export of
 * {@code branch_conditions.wat} is one comparison in one consumer shape; its compiled result must
 * match the interpreter on operands that exercise signedness, zero, and NaN handling.
 */
public class BranchConditionTest {

    private static final long[] I32_VALUES = {
        0, 1, -1, 2, Integer.MIN_VALUE, Integer.MAX_VALUE,
    };

    private static final long[] I64_VALUES = {
        0, 1, -1, 2, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 32, Integer.MIN_VALUE,
    };

    private static final long[] F32_VALUES = {
        Value.floatToLong(0.0f),
        Value.floatToLong(-0.0f),
        Value.floatToLong(1.0f),
        Value.floatToLong(-1.5f),
        Value.floatToLong(Float.NaN),
        Value.floatToLong(Float.POSITIVE_INFINITY),
        Value.floatToLong(Float.NEGATIVE_INFINITY),
    };

    private static final long[] F64_VALUES = {
        Value.doubleToLong(0.0),
        Value.doubleToLong(-0.0),
        Value.doubleToLong(1.0),
        Value.doubleToLong(-1.5),
        Value.doubleToLong(Double.NaN),
        Value.doubleToLong(Double.POSITIVE_INFINITY),
        Value.doubleToLong(Double.NEGATIVE_INFINITY),
    };

    // the boundaries of every constant in branch_conditions_const.wat, and their neighbors
    private static final long[] CONST_OPERAND_VALUES = {
        0,
        1,
        -1,
        2,
        -2,
        -3,
        3,
        6,
        7,
        8,
        9,
        254,
        255,
        256,
        257,
        (1 << 30) - 1,
        1 << 30,
        (1 << 30) + 1,
        Integer.MAX_VALUE - 1,
        Integer.MAX_VALUE,
        Integer.MIN_VALUE,
        Integer.MIN_VALUE + 1,
    };

    private static WasmModule module() {
        return Parser.parse(CorpusResources.getResource("compiled/branch_conditions.wat.wasm"));
    }

    private static WasmModule constModule() {
        return Parser.parse(
                CorpusResources.getResource("compiled/branch_conditions_const.wat.wasm"));
    }

    private static List<String> exportNames(WasmModule module) {
        var names = new ArrayList<String>();
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            names.add(module.exportSection().getExport(i).name());
        }
        return names;
    }

    private static long[] valuesFor(String name) {
        switch (name.substring(0, 3)) {
            case "i32":
                return I32_VALUES;
            case "i64":
                return I64_VALUES;
            case "f32":
                return F32_VALUES;
            case "f64":
                return F64_VALUES;
            default:
                throw new IllegalArgumentException(name);
        }
    }

    @Test
    public void compiledMatchesInterpreter() {
        var module = module();
        var interpreted = Instance.builder(module).build();
        var compiled =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();

        for (var name : exportNames(module)) {
            var values = valuesFor(name);
            for (long a : values) {
                for (long b : values) {
                    var expected = interpreted.export(name).apply(a, b);
                    var actual = compiled.export(name).apply(a, b);
                    assertArrayEquals(expected, actual, name + "(" + a + ", " + b + ") differs");
                }
            }
        }
    }

    @Test
    public void fusesComparisons() {
        var module = module();
        var analyzer = new WasmAnalyzer(module);
        var names = exportNames(module);
        for (int i = 0; i < names.size(); i++) {
            var name = names.get(i);
            int funcId = module.exportSection().getExport(i).index();
            var opcodes = new ArrayList<CompilerOpCode>();
            for (var ins : analyzer.analyze(funcId).instructions()) {
                opcodes.add(ins.opcode());
            }

            // unsigned i64 comparisons are fused through Long.compareUnsigned, i32 ones are not
            boolean unsigned = name.startsWith("i32_") && name.contains("_u_");
            boolean fused =
                    opcodes.contains(CompilerOpCode.COND_JUMP)
                            || opcodes.contains(CompilerOpCode.SELECT_COND);
            // unsigned and plain i32 conditions have nothing to fuse unless an eqz sits in between
            boolean fusible = name.contains("eqz") || !(unsigned || name.startsWith("i32_plain_"));
            assertEquals(fusible, fused, name + " fused jump: " + opcodes);
            assertEquals(
                    unsigned,
                    opcodes.stream().anyMatch(BranchConditionTest::isComparison),
                    name + " comparisons: " + opcodes);
            assertTrue(!opcodes.contains(CompilerOpCode.I32_EQZ), name + " kept eqz: " + opcodes);
            if (name.startsWith("i64_extend_")) {
                // (i64.eqz (i64.extend_i32_x b)) is absorbed like (i32.eqz b)
                assertFalse(
                        opcodes.contains(CompilerOpCode.I64_EXTEND_I32_U)
                                || opcodes.contains(CompilerOpCode.I64_EXTEND_I32_S),
                        name + " kept extend: " + opcodes);
            }
        }
    }

    private static boolean isComparison(CompilerOpCode opcode) {
        return BranchCondition.ofComparison(opcode).isPresent()
                || opcode == CompilerOpCode.I32_LT_U
                || opcode == CompilerOpCode.I32_GE_U
                || opcode == CompilerOpCode.I32_GT_U
                || opcode == CompilerOpCode.I32_LE_U;
    }

    @Test
    public void constantOperandsMatchInterpreter() {
        var module = constModule();
        var interpreted = Instance.builder(module).build();
        var compiled =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();

        for (var name : exportNames(module)) {
            for (long a : CONST_OPERAND_VALUES) {
                var expected = interpreted.export(name).apply(a);
                var actual = compiled.export(name).apply(a);
                assertArrayEquals(expected, actual, name + "(" + a + ") differs");
            }
        }
    }

    private enum ConstForm {
        /** rewritten to a test of the variable operand against zero */
        ZERO_TEST,
        /** rewritten to a shift of the variable operand tested against zero */
        SHIFT_TEST,
        /** fused as a two-operand comparison */
        COMPARISON,
        /** not fused: an unsigned i32 helper call */
        HELPER,
    }

    // the expected lowering of "x <op> c", derived independently of WasmAnalyzer
    private static ConstForm constForm(String op, int c) {
        if (!op.endsWith("_u")) {
            boolean zero =
                    c == 0
                            || (c == 1 && (op.equals("lt_s") || op.equals("ge_s")))
                            || (c == -1 && (op.equals("gt_s") || op.equals("le_s")));
            return zero ? ConstForm.ZERO_TEST : ConstForm.COMPARISON;
        }
        // x <u bound or its complement, for an unsigned (not wrapping) bound
        long bound = Integer.toUnsignedLong(c);
        if (op.equals("gt_u") || op.equals("le_u")) {
            bound++;
        }
        if (Long.bitCount(bound) != 1 || bound > 1L << 31) {
            return ConstForm.HELPER;
        }
        int shift = Long.numberOfTrailingZeros(bound);
        return shift == 0 || shift == 31 ? ConstForm.ZERO_TEST : ConstForm.SHIFT_TEST;
    }

    private static int constant(String name) {
        switch (name) {
            case "m1":
                return -1;
            case "m2":
                return -2;
            case "p30":
                return 1 << 30;
            case "max":
                return Integer.MAX_VALUE;
            case "min":
                return Integer.MIN_VALUE;
            default:
                return Integer.parseInt(name);
        }
    }

    @Test
    public void rewritesComparisonsAgainstConstants() {
        var module = constModule();
        var analyzer = new WasmAnalyzer(module);
        var names = exportNames(module);
        var zeroTests =
                List.of(
                        BranchCondition.I32_EQZ,
                        BranchCondition.I32_NEZ,
                        BranchCondition.I32_LTZ,
                        BranchCondition.I32_GTZ,
                        BranchCondition.I32_LEZ,
                        BranchCondition.I32_GEZ);
        for (int i = 0; i < names.size(); i++) {
            var name = names.get(i);
            // i32_<op>_c<constant>_<shape>
            var parts = name.split("_c", 2);
            var op = parts[0].substring("i32_".length());
            var form = constForm(op, constant(parts[1].substring(0, parts[1].indexOf('_'))));

            int funcId = module.exportSection().getExport(i).index();
            var opcodes = new ArrayList<CompilerOpCode>();
            var conditions = new ArrayList<BranchCondition>();
            for (var ins : analyzer.analyze(funcId).instructions()) {
                opcodes.add(ins.opcode());
                if (ins.opcode() == CompilerOpCode.COND_JUMP
                        || ins.opcode() == CompilerOpCode.SELECT_COND) {
                    conditions.add(BranchCondition.fromId(ins.operand(1)));
                }
            }

            var message = name + " (" + form + "): " + opcodes + " " + conditions;
            if (form == ConstForm.HELPER) {
                // only an i32.eqz around the helper call can have been absorbed
                assertTrue(
                        conditions.stream().allMatch(c -> c == BranchCondition.I32_NEZ), message);
            } else {
                assertFalse(conditions.isEmpty(), message);
            }
            assertEquals(
                    form == ConstForm.HELPER,
                    opcodes.stream().anyMatch(BranchConditionTest::isComparison),
                    message);
            assertEquals(
                    form == ConstForm.SHIFT_TEST,
                    opcodes.contains(CompilerOpCode.I32_SHR_U),
                    message);
            if (form == ConstForm.ZERO_TEST || form == ConstForm.SHIFT_TEST) {
                assertTrue(zeroTests.containsAll(conditions), message);
            }
        }
    }

    @Test
    public void negatedJumpsAreComplements() {
        int[][] complements = {
            {Opcodes.IFEQ, Opcodes.IFNE},
            {Opcodes.IFLT, Opcodes.IFGE},
            {Opcodes.IFGT, Opcodes.IFLE},
            {Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE},
            {Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE},
            {Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE},
            {Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE},
            {Opcodes.IFNULL, Opcodes.IFNONNULL},
        };
        for (var pair : complements) {
            assertEquals(pair[1], BranchCondition.negate(pair[0]));
            assertEquals(pair[0], BranchCondition.negate(pair[1]));
        }
        for (var condition : BranchCondition.values()) {
            int jump = condition.jumpIfTrue();
            assertEquals(
                    jump, BranchCondition.negate(BranchCondition.negate(jump)), condition.name());
        }
    }
}
