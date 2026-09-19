package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
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

    private static WasmModule module() {
        return Parser.parse(CorpusResources.getResource("compiled/branch_conditions.wat.wasm"));
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
    public void fusesSignedAndFloatComparisons() {
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

            boolean unsigned = name.contains("_u_");
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
        }
    }

    private static boolean isComparison(CompilerOpCode opcode) {
        return BranchCondition.ofComparison(opcode).isPresent()
                || opcode == CompilerOpCode.I32_LT_U
                || opcode == CompilerOpCode.I32_GE_U
                || opcode == CompilerOpCode.I64_LT_U;
    }
}
