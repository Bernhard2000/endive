package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.ExternalType;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;

/**
 * Runs every condition fixture under each combination of the opt-in lowerings (unsigned {@code
 * i32} compare fusion, branchless booleans) and compares the results, including traps, with the
 * interpreter.
 */
public class CompilerOptionsTest {

    private static final String[] FIXTURES = {
        "branch_conditions.wat.wasm",
        "branch_conditions_const.wat.wasm",
        "bool_values.wat.wasm",
        "narrowing.wat.wasm",
        "ref_conditions.wat.wasm",
        "ref_eq.wat.wasm",
    };

    private static final long[] I32 = {
        0, 1, -1, 2, 255, 256, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE + 1, -11
    };
    private static final long[] I64 = {
        0, 1, -1, 2, Long.MIN_VALUE, Long.MAX_VALUE, 1L << 32, 0xFFFFFFFFL, Integer.MIN_VALUE
    };
    private static final long[] F32 = {
        Value.floatToLong(0.0f),
        Value.floatToLong(-1.5f),
        Value.floatToLong(Float.NaN),
        Value.floatToLong(Float.POSITIVE_INFINITY),
    };
    private static final long[] F64 = {
        Value.doubleToLong(0.0),
        Value.doubleToLong(-1.5),
        Value.doubleToLong(Double.NaN),
        Value.doubleToLong(Double.NEGATIVE_INFINITY),
    };

    private static long[] values(ValType type) {
        switch (type.opcode()) {
            case ValType.ID.I64:
                return I64;
            case ValType.ID.F32:
                return F32;
            case ValType.ID.F64:
                return F64;
            default:
                return I32;
        }
    }

    private static String call(Instance instance, String name, long[] args) {
        try {
            var result = instance.export(name).apply(args);
            return result.length == 0 ? "[]" : Long.toString(result[0]);
        } catch (RuntimeException e) {
            var message = String.valueOf(e.getMessage());
            int colon = message.indexOf(':');
            return "trap: " + (colon < 0 ? message : message.substring(0, colon));
        }
    }

    private static List<long[]> argumentTuples(List<ValType> params) {
        var tuples = new ArrayList<long[]>();
        if (params.isEmpty()) {
            tuples.add(new long[0]);
        } else if (params.size() == 1) {
            for (long a : values(params.get(0))) {
                tuples.add(new long[] {a});
            }
        } else {
            for (long a : values(params.get(0))) {
                for (long b : values(params.get(1))) {
                    tuples.add(new long[] {a, b});
                }
            }
        }
        return tuples;
    }

    @Test
    public void everyOptionCombinationMatchesInterpreter() {
        for (var fixture : FIXTURES) {
            var module = Parser.parse(CorpusResources.getResource("compiled/" + fixture));
            var interpreted = Instance.builder(module).build();
            int imports = module.importSection().count(ExternalType.FUNCTION);
            for (int options = 0; options < 4; options++) {
                boolean unsigned = (options & 1) != 0;
                boolean branchless = (options & 2) != 0;
                var compiled =
                        Instance.builder(module)
                                .withMachineFactory(
                                        MachineFactoryCompiler.builder(module)
                                                .withUnsignedCompareFusion(unsigned)
                                                .withBranchlessBooleans(branchless)
                                                .compile())
                                .build();
                for (int i = 0; i < module.exportSection().exportCount(); i++) {
                    var export = module.exportSection().getExport(i);
                    if (export.exportType() != ExternalType.FUNCTION) {
                        continue;
                    }
                    var params =
                            module.functionSection()
                                    .getFunctionType(export.index() - imports, module.typeSection())
                                    .params();
                    for (var args : argumentTuples(params)) {
                        assertEquals(
                                call(interpreted, export.name(), args),
                                call(compiled, export.name(), args),
                                fixture
                                        + " "
                                        + export.name()
                                        + Arrays.toString(args)
                                        + " unsigned="
                                        + unsigned
                                        + " branchless="
                                        + branchless);
                    }
                }
            }
        }
    }

    private static int funcId(WasmModule module, String name) {
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            if (module.exportSection().getExport(i).name().equals(name)) {
                return module.exportSection().getExport(i).index();
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static List<CompilerInstruction> analyze(
            String fixture, String name, boolean unsigned, boolean branchless) {
        var module = Parser.parse(CorpusResources.getResource("compiled/" + fixture));
        return new WasmAnalyzer(module, unsigned, branchless)
                .analyze(funcId(module, name))
                .instructions();
    }

    private static List<CompilerOpCode> opcodes(List<CompilerInstruction> instructions) {
        return instructions.stream().map(CompilerInstruction::opcode).collect(Collectors.toList());
    }

    @Test
    public void unsignedComparisonsFuseOnlyWhenEnabled() {
        for (var op : new String[] {"lt_u", "gt_u", "le_u", "ge_u"}) {
            var name = "i32_" + op + "_if";
            var off = analyze("bool_values.wat.wasm", name, false, false);
            var on = analyze("bool_values.wat.wasm", name, true, false);
            var opcode = CompilerOpCode.valueOf("I32_" + op.toUpperCase(Locale.ROOT));
            assertTrue(opcodes(off).contains(opcode), name + ": " + opcodes(off));
            assertFalse(opcodes(on).contains(opcode), name + ": " + opcodes(on));
            assertTrue(
                    on.stream()
                            .anyMatch(
                                    ins ->
                                            ins.opcode() == CompilerOpCode.COND_JUMP
                                                    && BranchCondition.fromId(ins.operand(1))
                                                            .name()
                                                            .equals(opcode.name())),
                    name + ": " + opcodes(on));
        }
    }

    @Test
    public void branchlessBooleansReplaceValueComparisons() {
        for (var t : new String[] {"i32", "i64"}) {
            for (var op : new String[] {"eqz", "eq", "ne"}) {
                var name = t + "_" + op + "_value";
                var opcode = CompilerOpCode.valueOf((t + "_" + op).toUpperCase(Locale.ROOT));
                assertTrue(
                        opcodes(analyze("bool_values.wat.wasm", name, false, false))
                                .contains(opcode));
                var on = opcodes(analyze("bool_values.wat.wasm", name, false, true));
                assertFalse(on.contains(opcode), name + ": " + on);
                assertTrue(on.contains(CompilerOpCode.BRANCHLESS_BOOL), name + ": " + on);
            }
        }
    }
}
