import java.nio.file.Files;
import java.nio.file.Path;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;

/** Compiles MODULE.wasm with MachineFactoryCompiler and calls run(7, 1000) CALLS times. */
public class HotLoop {
    public static void main(String[] args) throws Exception {
        var module = Parser.parse(Files.readAllBytes(Path.of(args[0])));
        var run =
                Instance.builder(module)
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build()
                        .export("run");
        long acc = 0;
        int calls = Integer.parseInt(args[1]);
        long t0 = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            acc += run.apply(7, 1000)[0];
        }
        System.out.println("result=" + acc + " ms=" + (System.nanoTime() - t0) / 1_000_000);
    }
}
