import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes BenchmarkLargeMethod's generated module: WriteModule SITES ITERATIONS OUT.wasm */
public class WriteModule {
    public static void main(String[] args) throws Exception {
        Method wat =
                Class.forName("run.endive.bench.BenchmarkLargeMethod")
                        .getDeclaredMethod("wat", int.class, int.class);
        wat.setAccessible(true);
        var text = (String) wat.invoke(null, Integer.parseInt(args[0]), Integer.parseInt(args[1]));
        Files.write(Path.of(args[2]), run.endive.wabt.Wat2Wasm.parse(text));
    }
}
