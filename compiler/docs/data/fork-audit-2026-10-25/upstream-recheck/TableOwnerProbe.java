import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Machine;
import run.endive.runtime.Store;
import run.endive.wasm.Parser;

/** Upstream #242 regression: a table initialiser's entries belong to the defining instance. */
public final class TableOwnerProbe {
    public static void main(String[] args) throws Exception {
        var exporter = Files.readAllBytes(Path.of(args[0]));
        var importer = Files.readAllBytes(Path.of(args[1]));
        Function<Instance, Machine> interp = InterpreterMachine::new;
        Function<Instance, Machine> aot = MachineFactoryCompiler::compile;
        String[] names = {"interp", "aot"};
        Object[] factories = {interp, aot};
        for (int c = 0; c < 2; c++) {
            for (int d = 0; d < 2; d++) {
                @SuppressWarnings("unchecked")
                var callerFactory = (Function<Instance, Machine>) factories[c];
                @SuppressWarnings("unchecked")
                var definerFactory = (Function<Instance, Machine>) factories[d];
                String label = "caller=" + names[c] + " definer=" + names[d];
                try {
                    var store = new Store();
                    var definer = Instance.builder(Parser.parse(exporter))
                            .withMachineFactory(definerFactory).build();
                    store.register("test", definer);
                    var caller = Instance.builder(Parser.parse(importer))
                            .withImportValues(store.toImportValues())
                            .withMachineFactory(callerFactory).build();
                    long r = caller.export("call").apply(1)[0];
                    var owner = definer.table(0).instance(1);
                    System.out.println(label + " result=" + (int) r + " (expected 42) ownerRecorded="
                            + (owner == definer));
                } catch (Throwable t) {
                    System.out.println(label + " " + t.getClass().getSimpleName() + ": " + t.getMessage());
                }
            }
        }
    }
}
