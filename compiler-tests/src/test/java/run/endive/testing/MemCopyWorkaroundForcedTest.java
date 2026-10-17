package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;

/**
 * Runs compiled code with {@code -Dendive.memCopyWorkaround=true}, which enables the JIT bug
 * workaround on any Java version. The compiler-tests pom runs this class in its own execution
 * with the property set; without it, the test is skipped.
 */
public class MemCopyWorkaroundForcedTest {

    private static final String WAT =
            "(module (memory 1)"
                    + " (func (export \"copy\") (result i32)"
                    + "  (i32.store (i32.const 0) (i32.const 0x01020304))"
                    + "  (memory.copy (i32.const 8) (i32.const 0) (i32.const 4))"
                    + "  (i32.load (i32.const 8)))"
                    + " (func (export \"cmp\") (param i32 i32) (result i32)"
                    + "  (i32.or (i32.shl (i32.ge_u (local.get 0) (local.get 1)) (i32.const 1))"
                    + "   (i32.lt_u (local.get 0) (local.get 1)))))";

    @Test
    public void compiledCodeRunsWithForcedWorkaround() {
        assumeTrue(Boolean.getBoolean("endive.memCopyWorkaround"));
        var module = Parser.parse(Wat2Wasm.parse(WAT));
        var interpreted = Instance.builder(module).build();
        var compiled =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build();
        assertArrayEquals(new long[] {0x01020304}, compiled.export("copy").apply());
        long[][] operands = {{1, 2}, {2, 1}, {-1, 1}, {1, -1}, {0, 0}};
        for (long[] args : operands) {
            assertArrayEquals(
                    interpreted.export("cmp").apply(args), compiled.export("cmp").apply(args));
        }
    }
}
