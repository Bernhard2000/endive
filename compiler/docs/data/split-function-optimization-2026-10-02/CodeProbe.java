import java.nio.file.Files;
import java.nio.file.Path;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MethodPrefixer;
import run.endive.compiler.internal.Compiler;
import run.endive.wasm.Parser;
public class CodeProbe {
  public static void main(String[] args) throws Exception {
    var module = Parser.parse(Path.of(args[0]).toFile());
    var result = Compiler.builder(module).withMethodPrefixer(MethodPrefixer.fromNameSection())
        .withInterpreterFallback(InterpreterFallback.SILENT).build().compile();
    var root = Path.of(args[1]);
    Files.createDirectories(root);
    for (var entry : result.classBytes().entrySet()) {
      Files.write(root.resolve(entry.getKey().replace('/', '_') + ".class"), entry.getValue());
    }
    System.out.println("classes=" + result.classBytes().size() + " fallback=" + result.interpretedFunctions().size());
  }
}
