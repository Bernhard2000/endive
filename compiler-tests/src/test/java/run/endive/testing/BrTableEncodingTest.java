package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.compiler.internal.Compiler;
import run.endive.runtime.Instance;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmEngineException;
import run.endive.wasm.WasmModule;

/** br_table chooses the smaller JVM switch encoding without changing Wasm's default target. */
public class BrTableEncodingTest {

    private static WasmModule parse(String wat) {
        return Parser.parse(Wat2Wasm.parse(wat));
    }

    // Targets 0 and 1 return 11 and 22; target 2 and the Wasm default return 33.
    private static WasmModule dispatch(int[] targets) {
        var wat = new StringBuilder("(module (func (export \"run\") (param i32) (result i32)");
        wat.append(" (block $out (block $b (block $a local.get 0 br_table");
        for (int target : targets) {
            wat.append(target == 0 ? " $a" : target == 1 ? " $b" : " $out");
        }
        wat.append(" $out) i32.const 11 return) i32.const 22 return) i32.const 33))");
        return parse(wat.toString());
    }

    // Compares compiler and interpreter on every selector, including negative selectors, which
    // Wasm treats as large unsigned values.
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
        Arrays.fill(targets, 2);
        targets[0] = 0;
        targets[63] = 1;
        targets[127] = 0;
        var module = dispatch(targets);
        assertDispatch(module, targets);
        assertEquals(List.of("lookup:3"), switches(module));
    }

    // A dominant non-default target becomes the lookupswitch default only for tables that are
    // large on their own; selectors outside the table still reach the Wasm default.
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

    private static List<String> switches(WasmModule module) {
        var result = new ArrayList<String>();
        var compiled = Compiler.builder(module).build().compile();
        assertTrue(compiled.interpretedFunctions().isEmpty());
        var visitor =
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
                };
        for (var entry : compiled.classBytes().entrySet()) {
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
                                    return name.equals("func_0") ? visitor : null;
                                }
                            },
                            0);
        }
        return result;
    }
}
