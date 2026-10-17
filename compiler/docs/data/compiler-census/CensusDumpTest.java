package run.endive.compiler.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import run.endive.wasm.Parser;

/**
 * Writes every class the compiler generates for the census modules to {@code
 * -Dcensus.out=<dir>/<module>/}. Copy into compiler/src/test/java/run/endive/compiler/internal/
 * and run with {@code -Dtest=CensusDumpTest -Dcensus.out=<dir>}; {@code -Dcensus.modules} takes a
 * comma-separated list of paths (relative to the repository root or absolute) instead of the
 * default corpus.
 */
public class CensusDumpTest {
    static final String[] MODULES = {
        "wasm-corpus/src/main/resources/compiled/basic.dotnet.wasm",
        "wasm-corpus/src/main/resources/compiled/main.go.wasm",
        "wasm-corpus/src/main/resources/compiled/greet-wasi.rs.wasm",
        "wasm-corpus/src/main/resources/compiled/quickjs-provider.javy-dynamic.wasm",
        "wasm-corpus/src/main/resources/compiled/javy-demo.js.javy.wasm",
        "wasm/src/test/resources/wasm/code.wasm",
    };

    @Test
    public void dump() throws Exception {
        var outProp = System.getProperty("census.out");
        if (outProp == null) {
            return;
        }
        var out = Path.of(outProp);
        var root = Path.of(System.getProperty("user.dir")).getParent();
        var list = System.getProperty("census.modules");
        var modules = list == null ? MODULES : list.split(",");
        for (var path : modules) {
            var file = root.resolve(path);
            var module = Parser.parse(Files.readAllBytes(file));
            var dir = out.resolve(file.getFileName().toString());
            Files.createDirectories(dir);
            var result = Compiler.builder(module).build().compile();
            for (var e : result.classBytes().entrySet()) {
                Files.write(dir.resolve(e.getKey().replace('/', '_') + ".class"), e.getValue());
            }
        }
    }
}
