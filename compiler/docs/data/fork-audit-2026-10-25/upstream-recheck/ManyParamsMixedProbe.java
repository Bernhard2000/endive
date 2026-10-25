import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Machine;
import run.endive.runtime.Store;
import run.endive.wasm.Parser;

/** Boxed (>251 slot) call_indirect into another instance, for every engine pairing. */
public final class ManyParamsMixedProbe {
    public static void main(String[] args) throws Exception {
        var exporter = Files.readAllBytes(Path.of(args[0]));
        var importer = Files.readAllBytes(Path.of(args[1]));
        Function<Instance, Machine> interp = InterpreterMachine::new;
        Function<Instance, Machine> aot = MachineFactoryCompiler::compile;
        String[] names = {"interp", "aot"};
        Object[] factories = {interp, aot};
        Object host = new Object();
        for (int c = 0; c < 2; c++) {
            for (int d = 0; d < 2; d++) {
                @SuppressWarnings("unchecked")
                var callerFactory = (Function<Instance, Machine>) factories[c];
                @SuppressWarnings("unchecked")
                var calleeFactory = (Function<Instance, Machine>) factories[d];
                String label = "caller=" + names[c] + " callee=" + names[d];
                StringBuilder sb = new StringBuilder(label);
                try {
                    var store = new Store();
                    var callee = Instance.builder(Parser.parse(exporter))
                            .withMachineFactory(calleeFactory).build();
                    store.register("test", callee);
                    var caller = Instance.builder(Parser.parse(importer))
                            .withImportValues(store.toImportValues())
                            .withMachineFactory(callerFactory).build();
                    sb.append(" indirect_252=").append(caller.export("indirect_252").apply()[0]);
                    sb.append(" extern(host)=").append(caller.export("extern_indirect")
                            .applyWithRefs(new long[1], new Object[] {host}).longResult(0));
                    sb.append(" extern(null)=").append(caller.export("extern_indirect")
                            .applyWithRefs(new long[1], new Object[] {null}).longResult(0));
                } catch (Throwable t) {
                    sb.append(" ").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
                }
                System.out.println(sb + "   (expected 42 / 0 / 1)");
            }
        }
    }
}
