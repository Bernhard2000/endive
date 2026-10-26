import java.nio.file.Files;
import java.nio.file.Path;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.ExportFunction;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;

/** Drives repro.wasm like OpenJDK's TestFoldedIfsWrongReexec drives test1. */
public class Repro {
    public static void main(String[] args) throws Exception {
        var module = Parser.parse(Files.readAllBytes(Path.of(args[0])));
        var instance =
                Instance.builder(module).withMachineFactory(MachineFactoryCompiler::compile).build();
        ExportFunction helper = instance.export("helper");
        ExportFunction taken = instance.export("taken");
        ExportFunction reset = instance.export("reset");
        int bad = 0;
        for (String name : new String[] {"test1", "test2"}) {
            ExportFunction test = instance.export(name);
            reset.apply();
            String result;
            try {
                for (int i = 0; i < 20_000; i++) {
                    test.apply(12);
                    if (taken.apply()[0] != 0) {
                        throw new AssertionError("branch shouldn't have been taken");
                    }
                    helper.apply(16, 0);
                }
                test.apply(0);
                result = taken.apply()[0] == 1 ? "PASS" : "WRONG taken=" + taken.apply()[0];
            } catch (Throwable t) {
                result = "WRONG " + t;
            }
            if (!result.equals("PASS")) {
                bad++;
            }
            System.out.println(name + ": " + result);
        }
        System.exit(bad == 0 ? 0 : 1);
    }
}
