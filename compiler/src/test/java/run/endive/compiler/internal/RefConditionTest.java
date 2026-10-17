package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/**
 * Checks the fusion of reference null tests into the jumps and selects consuming them. Every
 * export of {@code ref_conditions.wat} builds a null or non-null reference from its selector
 * argument and tests it in one consumer shape: {@code ref.is_null} under {@code br_if}, {@code
 * if} and {@code select}, {@code br_on_null}, {@code br_on_non_null} and {@code br_on_cast_fail},
 * on object-represented ({@code struct}, {@code externref}, {@code anyref}) and int-represented
 * ({@code funcref}) references.
 */
public class RefConditionTest {

    // 0: null, 1: i31ref for $any, otherwise a struct
    private static final long[] SELECTORS = {0, 1, 2, 5};

    private static WasmModule module() {
        return Parser.parse(CorpusResources.getResource("compiled/ref_conditions.wat.wasm"));
    }

    private static List<String> exportNames(WasmModule module) {
        var names = new ArrayList<String>();
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            names.add(module.exportSection().getExport(i).name());
        }
        return names;
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
            for (long a : SELECTORS) {
                var expected = interpreted.export(name).apply(a);
                var actual = compiled.export(name).apply(a);
                assertArrayEquals(expected, actual, name + "(" + a + ") differs");
            }
        }
    }

    @Test
    public void fusesNullTests() {
        var module = module();
        var analyzer = new WasmAnalyzer(module);
        var names = exportNames(module);
        for (int i = 0; i < names.size(); i++) {
            var name = names.get(i);
            int funcId = module.exportSection().getExport(i).index();
            var instructions = analyzer.analyze(funcId).instructions();
            var opcodes = new ArrayList<CompilerOpCode>();
            var conditions = new ArrayList<BranchCondition>();
            for (var ins : instructions) {
                opcodes.add(ins.opcode());
                if (ins.opcode() == CompilerOpCode.COND_JUMP
                        || ins.opcode() == CompilerOpCode.SELECT_COND) {
                    conditions.add(BranchCondition.fromId(ins.operand(1)));
                }
            }
            var message = name + ": " + opcodes + " " + conditions;

            if (name.contains("_br_on_cast")) {
                // br_on_cast_fail branches on the inverted cast result, no 0/1 inversion
                assertTrue(
                        followedBy(
                                instructions,
                                CompilerOpCode.BR_ON_CAST_CHECK,
                                name.contains("_fail") ? CompilerOpCode.IFNE : CompilerOpCode.IFEQ),
                        message);
                continue;
            }

            var nullTest =
                    name.startsWith("func_")
                            ? BranchCondition.INT_REF_IS_NULL
                            : BranchCondition.REF_IS_NULL;
            if (name.endsWith("_is_null_value")) {
                // the result is a value, not a condition: nothing to fuse
                assertTrue(conditions.isEmpty(), message);
                assertTrue(opcodes.contains(CompilerOpCode.REF_IS_NULL), message);
                continue;
            }
            assertTrue(conditions.contains(nullTest), message);
            if (name.contains("_br_on_")) {
                // the tested copy comes from a DUP right before the fused jump
                assertTrue(
                        followedBy(instructions, CompilerOpCode.DUP, CompilerOpCode.COND_JUMP),
                        message);
            }
            // br_on_null_value and select_ref also compute ref.is_null as a plain value
            boolean valueUse = name.endsWith("_br_on_null_value") || name.endsWith("_select_ref");
            assertEquals(valueUse, opcodes.contains(CompilerOpCode.REF_IS_NULL), message);
            assertFalse(opcodes.contains(CompilerOpCode.I32_EQZ), message);
        }
    }

    private static boolean followedBy(
            List<CompilerInstruction> instructions, CompilerOpCode first, CompilerOpCode second) {
        for (int i = 0; i + 1 < instructions.size(); i++) {
            if (instructions.get(i).opcode() == first
                    && instructions.get(i + 1).opcode() == second) {
                return true;
            }
        }
        return false;
    }
}
