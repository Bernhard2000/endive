package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.compiler.internal.Compiler;
import run.endive.runtime.HostFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;
import run.endive.wasm.types.Value;

public class SplitFunctionOptimizationTest {

    private static WasmModule parse(String wat) {
        return Parser.parse(Wat2Wasm.parse(wat));
    }

    // Targets 0 and 1 return 11 and 22; the Wasm default target returns 33.
    private static WasmModule dispatch(int[] targets) {
        var wat = new StringBuilder("(module (func (export \"run\") (param i32) (result i32)");
        wat.append(" (block $out (block $b (block $a local.get 0 br_table");
        for (int target : targets) {
            wat.append(target == 0 ? " $a" : target == 1 ? " $b" : " $out");
        }
        wat.append(" $out) i32.const 11 return) i32.const 22 return) i32.const 33))");
        return parse(wat.toString());
    }

    private static void assertDispatch(WasmModule module, int[] targets) {
        var interpreted = Instance.builder(module).build();
        var compiled =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();
        for (int selector = -2; selector <= targets.length + 2; selector++) {
            long expected =
                    selector < 0 || selector >= targets.length
                            ? 33
                            : targets[selector] == 0 ? 11 : targets[selector] == 1 ? 22 : 33;
            assertArrayEquals(new long[] {expected}, interpreted.export("run").apply(selector));
            assertArrayEquals(new long[] {expected}, compiled.export("run").apply(selector));
        }
        for (int selector : new int[] {Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertArrayEquals(new long[] {33}, compiled.export("run").apply(selector));
        }
    }

    @Test
    public void sparseDefaultEntriesUseLookupSwitch() {
        int[] targets = new int[128];
        java.util.Arrays.fill(targets, 2);
        targets[0] = 0;
        targets[63] = 1;
        targets[127] = 0;
        var module = dispatch(targets);
        assertDispatch(module, targets);
        assertEquals(List.of("lookup:3"), switches(module));
    }

    @Test
    public void dominantTargetRetainsDistinctOutOfRangeDefault() {
        for (int size : new int[] {128, 4096}) {
            int[] targets = new int[size];
            targets[1] = 1;
            targets[size / 2] = 2;
            targets[size - 1] = 1;
            var module = dispatch(targets);
            assertDispatch(module, targets);
            assertEquals(
                    size == 128 ? List.of("table:128") : List.of("lookup:3"), switches(module));
        }
    }

    @Test
    public void halfDenseTableKeepsTableSwitchForSpeed() {
        // rustvm-base's exec_op: 160 entries, keys 0-9 and 100-159. lookupswitch would save 84
        // bytes but makes HotSpot's interpreter binary-search every dispatch
        int[] targets = new int[160];
        java.util.Arrays.fill(targets, 2);
        for (int i = 0; i < 160; i++) {
            if (i < 10 || i >= 100) {
                targets[i] = i % 2;
            }
        }
        var module = dispatch(targets);
        assertDispatch(module, targets);
        assertEquals(List.of("table:160"), switches(module));
    }

    @Test
    public void tableSwitchCostsAtMostTheSpeedBudget() {
        // 1000 entries, 300 keys: tableswitch would still dispatch faster, but costs 1604 bytes
        // more, beyond the budget, so the size saving wins
        int[] targets = new int[1000];
        java.util.Arrays.fill(targets, 2);
        for (int i = 0; i < 300; i++) {
            targets[i * 3] = i % 2;
        }
        var module = dispatch(targets);
        assertDispatch(module, targets);
        assertEquals(List.of("lookup:300"), switches(module));
    }

    @Test
    public void emptyUniformAndDenseTables() {
        for (int[] targets : new int[][] {new int[0], {2, 2, 2}, {0, 0, 0}, {0, 1, 0, 1}}) {
            var module = dispatch(targets);
            assertDispatch(module, targets);
            if (targets.length == 0 || targets[0] == 2) {
                assertTrue(switches(module).isEmpty());
            } else if (targets.length == 4) {
                assertEquals(List.of("table:4"), switches(module));
            }
        }
    }

    @Test
    public void backwardSwitchStillChecksInterruption() {
        var module = parse("(module (func (export \"run\") (loop $l i32.const 0 br_table $l)))");
        var compiled =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();
        Thread.currentThread().interrupt();
        try {
            var error =
                    assertThrows(WasmEngineException.class, () -> compiled.export("run").apply());
            assertEquals("Thread interrupted", error.getMessage());
        } finally {
            Thread.interrupted();
        }
    }

    private static WasmModule tuples() {
        return parse(
                "(module (func $leaf (param i32 i64 f32 f64) (result i32 i64 f32 f64) local.get 0"
                    + " local.get 1 local.get 2 local.get 3) (func (export \"implicit\") (param i32"
                    + " i64 f32 f64) (result i32 i64 f32 f64) local.get 0 local.get 1 local.get 2"
                    + " local.get 3 call $leaf) (func (export \"explicit\") (param i32 i64 f32 f64)"
                    + " (result i32 i64 f32 f64) local.get 0 local.get 1 local.get 2 local.get 3"
                    + " call $leaf return) (func (export \"consume\") (param i32 i64 f32 f64)"
                    + " (result i32) local.get 0 local.get 1 local.get 2 local.get 3 call $leaf"
                    + " drop drop drop) (func (export \"join\") (param i32) (result i32 i64 f32"
                    + " f64) (block $end (result i32 i64 f32 f64) i32.const 7 i64.const 8 f32.const"
                    + " 9 f64.const 10 local.get 0 br_if $end drop drop drop drop i32.const 11"
                    + " i64.const 12 f32.const 13 f64.const 14 call $leaf)))");
    }

    @Test
    public void helperTupleResultsPreserveValuesAndSharedReturns() {
        var module = tuples();
        var interpreted = Instance.builder(module).build();
        var compiled =
                Instance.builder(module)
                        .withMachineFactory(
                                MachineFactoryCompiler.builder(module)
                                        .withMaxFunctionsPerClass(1)
                                        .compile())
                        .build();
        for (long[] args :
                new long[][] {
                    {
                        Integer.MIN_VALUE,
                        Long.MAX_VALUE,
                        Value.floatToLong(-0.0f),
                        Value.doubleToLong(-0.0)
                    },
                    {
                        -1,
                        Long.MIN_VALUE,
                        Value.floatToLong(Float.NaN),
                        Value.doubleToLong(Double.NaN)
                    },
                    {42, 123, Value.floatToLong(Float.POSITIVE_INFINITY), Value.doubleToLong(-1.5)}
                }) {
            for (String name : List.of("implicit", "explicit", "consume")) {
                assertArrayEquals(
                        interpreted.export(name).apply(args), compiled.export(name).apply(args));
            }
        }
        for (int flag : new int[] {0, 1}) {
            assertArrayEquals(
                    interpreted.export("join").apply(flag), compiled.export("join").apply(flag));
        }
        assertTrue(calls(module, "func_1").stream().anyMatch(n -> n.startsWith("value_")));
        assertTrue(calls(module, "func_2").stream().anyMatch(n -> n.startsWith("value_")));
        // The shared return can also be entered by br_if, with individual values on the stack.
        assertTrue(calls(module, "func_4").stream().anyMatch(n -> n.startsWith("value_")));
    }

    @Test
    public void helperCanUseInterpreterFallback() {
        var module = tuples();
        var interpreted = Instance.builder(module).build();
        var compiled =
                Instance.builder(module)
                        .withMachineFactory(
                                MachineFactoryCompiler.builder(module)
                                        .withInterpretedFunctions(Set.of(0))
                                        .compile())
                        .build();
        long[] args = {42, 123, Value.floatToLong(-0.0f), Value.doubleToLong(1.25)};
        assertArrayEquals(
                interpreted.export("implicit").apply(args),
                compiled.export("implicit").apply(args));
    }

    @Test
    public void wideTupleResultsDoNotExceedJvmParameterLimit() {
        for (int count : new int[] {127, 128, 130}) {
            var wat = new StringBuilder("(module (func $leaf (result i32");
            wat.append(" i64".repeat(count)).append(") i32.const 42");
            for (int i = 0; i < count; i++) {
                wat.append(" i64.const ").append(i - 64);
            }
            wat.append(") (func (export \"run\") (result i32");
            wat.append(" i64".repeat(count)).append(") call $leaf))");
            var module = parse(wat.toString());
            var interpreted = Instance.builder(module).build();
            var compiled =
                    Instance.builder(module)
                            .withMachineFactory(MachineFactoryCompiler::compile)
                            .build();
            assertArrayEquals(interpreted.export("run").apply(), compiled.export("run").apply());
        }
    }

    @Test
    public void referenceExceptionAndTailCallResults() {
        var module =
                Parser.parse(
                        SplitFunctionOptimizationTest.class.getResourceAsStream(
                                "split-helper-returns.wasm"));
        var interpreted = Instance.builder(module).build();
        var compiled =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();
        for (String name : List.of("gc-value", "gc-tail-value", "wide-gc-value")) {
            assertArrayEquals(
                    interpreted.export(name).apply(), compiled.export(name).apply(), name);
        }
        for (long flag : new long[] {0, 1, -1}) {
            assertArrayEquals(
                    interpreted.export("catch-forward").apply(flag),
                    compiled.export("catch-forward").apply(flag));
            assertArrayEquals(
                    interpreted.export("tail-forward").apply(flag),
                    compiled.export("tail-forward").apply(flag));
        }
        assertTrue(calls(module, "func_1").stream().anyMatch(n -> n.startsWith("value_")));
        assertTrue(calls(module, "func_7").stream().anyMatch(n -> n.startsWith("value_")));
    }

    @Test
    public void hostTupleResultsKeepNumericNormalization() {
        var module =
                parse(
                        "(module (import \"host\" \"tuple\" (func $host (result i32 i64))) (func"
                                + " (export \"run\") (result i32 i64) call $host))");
        var imports =
                ImportValues.builder()
                        .addFunction(
                                new HostFunction(
                                        "host",
                                        "tuple",
                                        FunctionType.of(
                                                List.of(), List.of(ValType.I32, ValType.I64)),
                                        (instance, args) ->
                                                new long[] {0x1_ffff_ffffL, Long.MIN_VALUE}))
                        .build();
        var compiled =
                Instance.builder(module)
                        .withImportValues(imports)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();
        assertArrayEquals(new long[] {-1, Long.MIN_VALUE}, compiled.export("run").apply());
        assertTrue(calls(module, "func_1").stream().anyMatch(n -> n.startsWith("value_")));
    }

    @Test
    public void actualBinaryenTupleAndMailboxHelpers() {
        for (String arm : List.of("tuples", "mailbox")) {
            var module =
                    Parser.parse(
                            SplitFunctionOptimizationTest.class.getResourceAsStream(
                                    "binaryen-split-" + arm + ".wasm"));
            // The source has three functions; a no-op splitter invocation cannot pass this gate.
            assertTrue(module.functionSection().functionCount() > 3);
            var compiled =
                    Instance.builder(module)
                            .withMachineFactory(MachineFactoryCompiler::compile)
                            .build();
            var interpreted = Instance.builder(module).build();
            // _start checks both routes of each outlined parent and traps on wrong results.
            assertArrayEquals(
                    interpreted.export("_start").apply(), compiled.export("_start").apply());
            var result = Compiler.builder(module).build().compile();
            assertTrue(result.interpretedFunctions().isEmpty());
        }
    }

    private static List<String> switches(WasmModule module) {
        var result = new ArrayList<String>();
        visit(
                module,
                "func_0",
                new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTableSwitchInsn(
                            int min, int max, Label dflt, Label... labels) {
                        result.add("table:" + labels.length);
                    }

                    @Override
                    public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
                        result.add("lookup:" + keys.length);
                    }
                });
        return result;
    }

    private static List<String> calls(WasmModule module, String method) {
        var result = new ArrayList<String>();
        visit(
                module,
                method,
                new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(
                            int opcode,
                            String owner,
                            String name,
                            String descriptor,
                            boolean isInterface) {
                        result.add(name);
                    }
                });
        return result;
    }

    private static void visit(WasmModule module, String method, MethodVisitor visitor) {
        var result = Compiler.builder(module).build().compile();
        assertTrue(result.interpretedFunctions().isEmpty());
        for (var entry : result.classBytes().entrySet()) {
            if (!entry.getKey().contains("FuncGroup")) {
                continue;
            }
            new ClassReader(entry.getValue())
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public MethodVisitor visitMethod(
                                        int access,
                                        String name,
                                        String descriptor,
                                        String signature,
                                        String[] exceptions) {
                                    return name.equals(method) ? visitor : null;
                                }
                            },
                            0);
        }
    }
}
