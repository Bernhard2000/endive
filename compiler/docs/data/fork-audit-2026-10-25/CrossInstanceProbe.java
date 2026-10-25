import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.Store;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;
public class CrossInstanceProbe {
  static WasmModule load(String path) throws Exception {
    return Parser.parse(Files.readAllBytes(Path.of(path)));
  }
  static Instance instance(WasmModule module, ImportValues imports, boolean compiled) {
    var b=Instance.builder(module).withImportValues(imports);
    if(compiled)b.withMachineFactory(MachineFactoryCompiler.builder(module)
        .withInterpreterFallback(InterpreterFallback.FAIL).compile());
    return b.build();
  }
  public static void main(String[] args) throws Exception {
    var provider=load(args[0]);var consumer=load(args[1]);
    for(boolean compiled:new boolean[]{false,true}) {
      var store=new Store();
      store.register("provider",instance(provider,ImportValues.builder().build(),compiled));
      var instance=instance(consumer,store.toImportValues(),compiled);
      for(String name:new String[]{"ordinary","tail"}) {
        try {System.out.println((compiled?"compiler":"interpreter")+" "+name+" "+Arrays.toString(instance.export(name).apply()));}
        catch(Exception e){System.out.println((compiled?"compiler":"interpreter")+" "+name+" "+e.getClass().getSimpleName()+": "+e.getMessage());}
      }
    }
  }
}
