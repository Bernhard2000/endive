import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/** Interpreter vs compiler (all option combinations) on every export and input pair. */
public final class FuseDiff {
    static final long[] I32 = {0, 1, -1, 2, 3, 255, 256, 257, 0x7fffffff, 0x80000000L, 0x80000001L,
        0xfffffffeL, 65535, 65536, 0x40000000, 65532};
    static final long[] I64 = {0, 1, -1, 1L << 31, 1L << 32, Long.MIN_VALUE, Long.MAX_VALUE,
        0xffffffffL, -(1L << 32), 5};
    static final float[] F = {0f, -0f, 1f, -1f, Float.NaN, Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY, 1.5f, Float.MIN_VALUE};
    static final double[] D = {0d, -0d, 1d, -1d, Double.NaN, Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY, 1.5d, Double.MIN_VALUE};

    static String run(Instance inst, String name, long[] args) {
        try {
            return Long.toString(inst.export(name).apply(args)[0] & 0xffffffffL);
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    public static void main(String[] a) throws Exception {
        WasmModule module = Parser.parse(Files.readAllBytes(Path.of(a[0])));
        List<String> names = new ArrayList<>();
        for (int i = 0; i < module.exportSection().exportCount(); i++) {
            names.add(module.exportSection().getExport(i).name());
        }
        var ref = Instance.builder(module).withMachineFactory(InterpreterMachine::new).build();
        Instance[] compiled = new Instance[4];
        for (int k = 0; k < 4; k++) {
            boolean ucf = (k & 1) != 0, bb = (k & 2) != 0;
            compiled[k] = Instance.builder(module).withMachineFactory(
                    MachineFactoryCompiler.builder(module).withUnsignedCompareFusion(ucf)
                            .withBranchlessBooleans(bb)
                            .withInterpreterFallback(InterpreterFallback.FAIL).compile()).build();
        }
        long checks = 0, mismatches = 0;
        for (String name : names) {
            String kind = name.substring(0, name.indexOf('_'));
            List<long[]> inputs = new ArrayList<>();
            switch (kind) {
                case "i": for (long x : I32) inputs.add(new long[] {x}); break;
                case "ii": for (long x : I32) for (long y : I32) inputs.add(new long[] {x, y}); break;
                case "ll": for (long x : I64) for (long y : I64) inputs.add(new long[] {x, y}); break;
                case "ff": for (float x : F) for (float y : F)
                    inputs.add(new long[] {Float.floatToRawIntBits(x) & 0xffffffffL,
                        Float.floatToRawIntBits(y) & 0xffffffffL}); break;
                case "dd": for (double x : D) for (double y : D)
                    inputs.add(new long[] {Double.doubleToRawLongBits(x), Double.doubleToRawLongBits(y)}); break;
                default: throw new IllegalStateException(name);
            }
            for (long[] in : inputs) {
                String expected = run(ref, name, in);
                for (int k = 0; k < 4; k++) {
                    checks++;
                    String got = run(compiled[k], name, in);
                    if (!got.equals(expected)) {
                        if (mismatches++ < 40) {
                            System.out.println("MISMATCH " + name + " ucf=" + ((k & 1) != 0) + " bb="
                                + ((k & 2) != 0) + " in=" + java.util.Arrays.toString(in)
                                + " interp=" + expected + " compiler=" + got);
                        }
                    }
                }
            }
        }
        System.out.println("exports=" + names.size() + " checks=" + checks + " mismatches=" + mismatches);
    }
}
