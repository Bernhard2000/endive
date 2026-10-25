import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import run.endive.compiler.InterpreterFallback;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.Instance;
import run.endive.wasm.Parser;
public class CancellationProbe {
  public static void main(String[] args) throws Exception {
    var bytes = Files.readAllBytes(Path.of(args[0]));
    var module = Parser.parse(bytes);
    var factory = MachineFactoryCompiler.builder(module)
        .withInterpreterFallback(InterpreterFallback.FAIL).compile();
    for (String name : new String[] {"ordinary_loop", "tail_direct", "tail_indirect", "tail_ref"}) {
      for (boolean compiled : new boolean[] {false,true}) {
        var builder = Instance.builder(module);
        if (compiled) builder.withMachineFactory(factory);
        var instance = builder.build();
        var started = new CountDownLatch(1);
        var outcome = new AtomicReference<String>("running");
        Thread worker = new Thread(() -> {
          started.countDown();
          try { instance.export(name).apply(); outcome.set("returned"); }
          catch (Throwable e) { outcome.set(e.getClass().getSimpleName()); }
        });
        worker.setDaemon(true);
        worker.start(); started.await(); Thread.sleep(100);
        worker.interrupt(); worker.join(1000);
        System.out.println(name + " " + (compiled?"compiler":"interpreter")
            + " alive_after_interrupt="+worker.isAlive()+" outcome="+outcome.get());
      }
    }
  }
}
