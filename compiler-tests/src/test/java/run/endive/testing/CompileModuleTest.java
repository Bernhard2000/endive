package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static run.endive.corpus.WatGenerator.methodTooLarge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import run.endive.compiler.Cache;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.CompiledModule;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.CodeSection;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

public class CompileModuleTest {

    // Each export reaches function 0 differently when it is the only interpreted function.
    private static final WasmModule DROP_BODIES =
            Parser.parse(CorpusResources.getResource("compiled/drop_bodies.wat.wasm"));
    private static final int TAIL_CALL = 3;
    private static final int CALL_REF = 4;
    private static final int TAIL_CALL_REF = 5;

    private static MachineFactoryCompiler.Builder interpreting(int funcId) {
        return MachineFactoryCompiler.builder(DROP_BODIES).withInterpretedFunctions(Set.of(funcId));
    }

    private static long call(CompiledModule compiled, String export) {
        return Instance.builder(compiled).build().export(export).apply(41)[0];
    }

    private static void assertRetained(CodeSection code, Set<Integer> retained) {
        var bodies = code.functionBodies();
        for (int i = 0; i < bodies.length; i++) {
            assertEquals(retained.contains(i), bodies[i] != null, "function body " + i);
        }
    }

    @Test
    public void keepsOnlyInterpretedFunctionBodies() {
        // func_1 (function index 2, code index 1) is too large to compile and is interpreted.
        var module = Parser.parse(Wat2Wasm.parse(methodTooLarge(20_000)));
        var compiled =
                MachineFactoryCompiler.builder(module)
                        .withInterpreterFallback(InterpreterFallback.SILENT)
                        .compileModule();

        var code = compiled.wasmModule().codeSection();
        assertEquals(3, code.functionBodyCount());
        assertRetained(code, Set.of(1));
        assertThrows(IllegalStateException.class, () -> code.getFunctionBody(0));
        // The module passed to the compiler is left unchanged.
        assertRetained(module.codeSection(), Set.of(0, 1, 2));

        var hostFunc =
                new HostFunction(
                        "funcs",
                        "host_func",
                        FunctionType.of(List.of(ValType.I32), List.of(ValType.I32)),
                        (inst, args) -> new long[] {35});
        var instance =
                Instance.builder(compiled)
                        .withImportValues(ImportValues.builder().addFunction(hostFunc).build())
                        .withStart(false)
                        .build();
        // Through the interpreted func_1, calling back into compiled code directly...
        assertEquals(35, instance.export("func_2").apply(0)[0]);
        // ...and through call_indirect.
        assertEquals(35, instance.export("func_2").apply(1)[0]);
    }

    @Test
    public void keepsFunctionsReachableThroughTailCalls() {
        var compiled = interpreting(TAIL_CALL).compileModule();

        assertRetained(compiled.wasmModule().codeSection(), Set.of(1, 2, TAIL_CALL));
        assertEquals(42, call(compiled, "tail_call"));
    }

    @Test
    public void callRefRunsCompiledFunction() {
        var compiled = interpreting(CALL_REF).compileModule();

        assertRetained(compiled.wasmModule().codeSection(), Set.of(CALL_REF));
        assertEquals(42, call(compiled, "call_ref"));
    }

    @Test
    public void keepsAllBodiesOnIndirectTailCall() {
        var compiled = interpreting(TAIL_CALL_REF).compileModule();

        assertRetained(compiled.wasmModule().codeSection(), Set.of(0, 1, 2, 3, 4, 5));
        assertEquals(42, call(compiled, "tail_call_ref"));
    }

    @Test
    public void dropsCustomSections() {
        assertNotNull(DROP_BODIES.nameSection());

        var compiled = interpreting(TAIL_CALL).compileModule();

        assertTrue(compiled.wasmModule().customSections().isEmpty());
    }

    @Test
    public void dropsBodiesOnCacheHit() {
        var cache = new MemoryCache();
        interpreting(TAIL_CALL).withCache(cache).compileModule();

        var compiled = interpreting(TAIL_CALL).withCache(cache).compileModule();

        assertEquals(1, cache.hits);
        assertRetained(compiled.wasmModule().codeSection(), Set.of(1, 2, TAIL_CALL));
        assertEquals(42, call(compiled, "tail_call"));
    }

    @Test
    public void compileKeepsTheModule() {
        var factory = interpreting(CALL_REF).compile();

        assertSame(DROP_BODIES, ((CompiledModule) factory).wasmModule());
    }

    @Test
    public void instanceRejectsDroppedBody() {
        var instance = Instance.builder(interpreting(CALL_REF).compileModule()).build();

        var exception = assertThrows(IllegalStateException.class, () -> instance.function(0));
        assertTrue(exception.getMessage().contains("dropped"));
    }

    private static final class MemoryCache implements Cache {
        private final Map<String, byte[]> entries = new HashMap<>();
        private int hits;

        @Override
        public byte[] get(String key) {
            var data = entries.get(key);
            if (data != null) {
                hits++;
            }
            return data;
        }

        @Override
        public void putIfAbsent(String key, byte[] data) {
            entries.putIfAbsent(key, data);
        }
    }
}
