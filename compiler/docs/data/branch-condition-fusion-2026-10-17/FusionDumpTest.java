package run.endive.compiler.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import run.endive.wasm.Parser;

public class FusionDumpTest {
    static final String ROOT = "/home/bernhard/Dokumente/MasterThesis/Endive/fork/endive/";
    static final String[] MODULES = {
        "wasm-corpus/src/main/resources/compiled/basic.dotnet.wasm",
        "wasm-corpus/src/main/resources/compiled/main.go.wasm",
        "wasm-corpus/src/main/resources/compiled/greet-wasi.rs.wasm",
        "wasm-corpus/src/main/resources/compiled/quickjs-provider.javy-dynamic.wasm",
        "wasm-corpus/src/main/resources/compiled/javy-demo.js.javy.wasm",
        "wasm/src/test/resources/wasm/code.wasm",
        "wasm-corpus/src/main/resources/compiled/ref_conditions.wat.wasm",
        "wasm-corpus/src/main/resources/compiled/gc.wat.wasm",
    };

    @Test
    public void dump() throws Exception {
        var out = Path.of(System.getProperty("fusion.out"));
        for (var path : MODULES) {
            var module = Parser.parse(Files.readAllBytes(Path.of(ROOT + path)));
            var name = Path.of(path).getFileName().toString();
            var dir = out.resolve(name);
            Files.createDirectories(dir);
            var result = Compiler.builder(module).build().compile();
            for (var e : result.classBytes().entrySet()) {
                Files.write(dir.resolve(e.getKey().replace('/', '_') + ".class"), e.getValue());
            }
        }
    }
}
