import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

public class Probe {
  public static void main(String[] args) throws Exception {
    byte[] bytes = Files.readAllBytes(Path.of(args[0]));
    WasmModule module = Parser.parse(bytes);
    Instance interpreter = Instance.builder(module).build();
    Instance compiled = Instance.builder(module).withMachineFactory(
        MachineFactoryCompiler.builder(module).withInterpreterFallback(InterpreterFallback.FAIL).compile()).build();
    for (int i=0; i<module.exportSection().exportCount(); i++) {
      var export = module.exportSection().getExport(i);
      if (export.exportType() == run.endive.wasm.types.ExternalType.FUNCTION) {
        System.out.println(export.name() + " interpreter=" + Arrays.toString(interpreter.export(export.name()).apply())
            + " compiler=" + Arrays.toString(compiled.export(export.name()).apply()));
      }
    }
  }
}
