package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/**
 * Checks {@link NarrowingPeephole}. Every export of {@code narrowing.wat} is one rewritten shape
 * or a near miss; compiled results, including traps, must match the interpreter.
 */
public class NarrowingPeepholeTest {

    private static final long[] VALUES = {
        0,
        1,
        -1,
        2,
        3,
        7,
        0xFFFF,
        0x10000,
        65533,
        65535,
        65536,
        Integer.MIN_VALUE,
        Integer.MAX_VALUE,
    };

    private static WasmModule module() {
        return Parser.parse(CorpusResources.getResource("compiled/narrowing.wat.wasm"));
    }

    private static List<String> exportNames(WasmModule module) {
        var names = new ArrayList<String>();
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            if (module.exportSection().getExport(i).name().equals("memory")) {
                continue;
            }
            names.add(module.exportSection().getExport(i).name());
        }
        return names;
    }

    private static int funcId(WasmModule module, String name) {
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            if (module.exportSection().getExport(i).name().equals(name)) {
                return module.exportSection().getExport(i).index();
            }
        }
        throw new IllegalArgumentException(name);
    }

    private static String call(Instance instance, String name, long... args) {
        try {
            var result = instance.export(name).apply(args);
            return result.length == 0 ? "[]" : Long.toString(result[0]);
        } catch (RuntimeException e) {
            // the compiler's messages add detail after the interpreter's
            var message = String.valueOf(e.getMessage());
            int colon = message.indexOf(':');
            return "trap: " + (colon < 0 ? message : message.substring(0, colon));
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
            // the fixture has no imports, so function and export indices coincide
            int params =
                    module.functionSection()
                            .getFunctionType(funcId(module, name), module.typeSection())
                            .params()
                            .size();
            for (long a : VALUES) {
                if (params == 1) {
                    assertEquals(
                            call(interpreted, name, a),
                            call(compiled, name, a),
                            name + "(" + a + ")");
                    continue;
                }
                for (long b : VALUES) {
                    assertEquals(
                            call(interpreted, name, a, b),
                            call(compiled, name, a, b),
                            name + "(" + a + ", " + b + ")");
                }
            }
        }
    }

    private static List<CompilerOpCode> opcodes(WasmModule module, String name) {
        return new WasmAnalyzer(module)
                .analyze(funcId(module, name)).instructions().stream()
                        .map(CompilerInstruction::opcode)
                        .collect(Collectors.toList());
    }

    @Test
    public void rewritesNarrowedI64Arithmetic() {
        var module = module();
        for (var name : exportNames(module)) {
            var ops = opcodes(module, name);
            if (name.startsWith("wrap_") && !name.contains("load")) {
                assertFalse(ops.contains(CompilerOpCode.I32_WRAP_I64), name + ": " + ops);
                assertFalse(ops.contains(CompilerOpCode.I64_CONST), name + ": " + ops);
                assertFalse(ops.contains(CompilerOpCode.I64_EXTEND_I32_U), name + ": " + ops);
                assertFalse(ops.contains(CompilerOpCode.I64_EXTEND_I32_S), name + ": " + ops);
                assertTrue(ops.contains(CompilerOpCode.I32_CONST), name + ": " + ops);
            } else if (name.startsWith("wrap_")) {
                assertFalse(ops.contains(CompilerOpCode.I32_WRAP_I64), name + ": " + ops);
                assertTrue(
                        ops.stream().noneMatch(op -> op.name().startsWith("I64_LOAD")),
                        name + ": " + ops);
            } else if (name.startsWith("ext_")) {
                assertFalse(ops.contains(CompilerOpCode.I64_EXTEND_I32_U), name + ": " + ops);
                assertTrue(ops.contains(CompilerOpCode.I64_EXTEND_I32_S), name + ": " + ops);
            }
        }
        assertTrue(opcodes(module, "keep_no_wrap").contains(CompilerOpCode.I64_EXTEND_I32_U));
        assertTrue(opcodes(module, "keep_var").contains(CompilerOpCode.I32_WRAP_I64));
        assertTrue(opcodes(module, "keep_shl").contains(CompilerOpCode.I32_WRAP_I64));
        assertTrue(opcodes(module, "keep_load64").contains(CompilerOpCode.I64_LOAD));
        assertTrue(opcodes(module, "keep_ext_value").contains(CompilerOpCode.I64_EXTEND_I32_U));
    }
}
