package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.ValType;

/**
 * Checks {@code ref.eq} fusion. It may only become {@code if_acmpeq} when one operand's static
 * type excludes {@code i31ref}, which {@code Shaded.refEq} compares by value. The fixture's
 * {@code select_t}, block/if merge and loop parameter exports type an operand that can hold an
 * {@code i31ref} narrower on one path; they must not fuse.
 */
public class RefEqConditionTest {

    private static final String[] FUSED = {"s_eq_if", "eq_s_if_eqz", "s_s_br_if", "s_eq_select"};
    private static final String[] NOT_FUSED = {
        "eq_eq_if",
        "eq_eq_select",
        "select_t_eq_if",
        "block_merge_eq_if",
        "if_merge_eq_if",
        "loop_param_eq_if",
        "loop_param_eq_br_if",
        "nested_loop_param_eq_if"
    };

    private static WasmModule module() {
        return Parser.parse(CorpusResources.getResource("compiled/ref_eq.wat.wasm"));
    }

    private static int funcId(WasmModule module, String name) {
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            if (module.exportSection().getExport(i).name().equals(name)) {
                return module.exportSection().getExport(i).index();
            }
        }
        throw new IllegalArgumentException(name);
    }

    @Test
    public void compiledMatchesInterpreter() {
        var module = module();
        var interpreted = Instance.builder(module).build();
        var compiled =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            var name = module.exportSection().getExport(i).name();
            for (long a = 0; a < 8; a++) {
                for (long b = 0; b < 8; b++) {
                    assertArrayEquals(
                            interpreted.export(name).apply(a, b),
                            compiled.export(name).apply(a, b),
                            name + "(" + a + ", " + b + ")");
                }
            }
        }
        // two distinct i31refs of the same value are equal
        assertArrayEquals(new long[] {1}, compiled.export("eq_eq_if").apply(1, 2));
        assertArrayEquals(new long[] {1}, compiled.export("select_t_eq_if").apply(1, 1));
        assertArrayEquals(new long[] {1}, compiled.export("block_merge_eq_if").apply(1, 1));
        assertArrayEquals(new long[] {1}, compiled.export("if_merge_eq_if").apply(1, 1));
        // the loop's first entry carries a struct, the back edge an i31ref equal to the operand
        assertArrayEquals(new long[] {1}, compiled.export("loop_param_eq_if").apply(1, 2));
        assertArrayEquals(new long[] {1}, compiled.export("loop_param_eq_br_if").apply(1, 2));
        assertArrayEquals(new long[] {1}, compiled.export("nested_loop_param_eq_if").apply(1, 2));
    }

    private static List<CompilerInstruction> instructions(WasmModule module, String name) {
        return new WasmAnalyzer(module).analyze(funcId(module, name)).instructions();
    }

    private static boolean hasRefEqCondition(List<CompilerInstruction> instructions) {
        return instructions.stream()
                .anyMatch(
                        ins ->
                                (ins.opcode() == CompilerOpCode.COND_JUMP
                                                || ins.opcode() == CompilerOpCode.SELECT_COND)
                                        && BranchCondition.fromId(ins.operand(1))
                                                == BranchCondition.REF_EQ);
    }

    @Test
    public void fusesOnlyWhenI31IsExcluded() {
        var module = module();
        for (var name : FUSED) {
            var instructions = instructions(module, name);
            var ops =
                    instructions.stream()
                            .map(CompilerInstruction::opcode)
                            .collect(Collectors.toList());
            assertTrue(hasRefEqCondition(instructions), name + ": " + ops);
            assertFalse(ops.contains(CompilerOpCode.REF_EQ), name + ": " + ops);
            assertFalse(ops.contains(CompilerOpCode.REF_EQ_FUSABLE), name + ": " + ops);
        }
        for (var name : NOT_FUSED) {
            var instructions = instructions(module, name);
            var ops =
                    instructions.stream()
                            .map(CompilerInstruction::opcode)
                            .collect(Collectors.toList());
            assertFalse(hasRefEqCondition(instructions), name + ": " + ops);
            assertTrue(ops.contains(CompilerOpCode.REF_EQ), name + ": " + ops);
        }
        var value = instructions(module, "s_eq_value").stream().map(CompilerInstruction::opcode);
        assertTrue(value.anyMatch(op -> op == CompilerOpCode.REF_EQ_FUSABLE));
    }

    @Test
    public void canBeI31() {
        assertTrue(WasmAnalyzer.canBeI31(ValType.EqRef));
        assertTrue(WasmAnalyzer.canBeI31(ValType.AnyRef));
        assertFalse(WasmAnalyzer.canBeI31(ValType.StructRef));
        assertFalse(WasmAnalyzer.canBeI31(ValType.ArrayRef));
    }
}
