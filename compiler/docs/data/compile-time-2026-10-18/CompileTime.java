import java.nio.file.Files;
import java.nio.file.Path;
import run.endive.compiler.internal.Compiler;
import run.endive.wasm.Parser;

/**
 * Times Compiler.compile() on one module: the first compile of a fresh JVM (what an
 * instantiation with MachineFactoryCompiler pays) and the median of later compiles once the
 * compiler itself is JIT-compiled. Usage: CompileTime MODULE.wasm [ITERATIONS]
 */
public class CompileTime {
    public static void main(String[] args) throws Exception {
        var bytes = Files.readAllBytes(Path.of(args[0]));
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        var module = Parser.parse(bytes);
        long[] ms = new long[iterations];
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            Compiler.builder(module).build().compile();
            ms[i] = (System.nanoTime() - t0) / 1000;
        }
        long first = ms[0];
        java.util.Arrays.sort(ms, Math.min(1, iterations - 1), iterations);
        long median = ms[(Math.min(1, iterations - 1) + iterations) / 2];
        System.out.println("first_us=" + first + " median_later_us=" + median);
    }
}
