package run.endive.bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.ExportFunction;
import run.endive.runtime.Instance;
import run.endive.wabt.Wat2Wasm;
import run.endive.wasm.Parser;

/** A br_table with a few rare targets among thousands of entries for one common target. */
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(2)
@State(Scope.Thread)
public class BenchmarkBrTableDispatch {

    @Param({"128", "4096"})
    public int entries;

    private ExportFunction run;
    private int selector;

    @Setup
    public void setup() {
        var wat = new StringBuilder("(module (func (export \"run\") (param i32) (result i32)");
        wat.append(" (block $out (block $rare (block $common local.get 0 br_table");
        for (int i = 0; i < entries; i++) {
            wat.append(i == 1 || i == entries / 2 || i == entries - 1 ? " $rare" : " $common");
        }
        wat.append(" $out) i32.const 11 return) i32.const 22 return) i32.const 33))");
        run =
                Instance.builder(Parser.parse(Wat2Wasm.parse(wat.toString())))
                        .withMachineFactory(MachineFactoryCompiler::compile)
                        .build()
                        .export("run");
        if (run.apply(0)[0] != 11 || run.apply(1)[0] != 22 || run.apply(-1)[0] != 33) {
            throw new IllegalStateException("Incorrect dispatch benchmark result");
        }
    }

    @Benchmark
    public long[] sparseDispatch() {
        selector = (selector + 1) & (entries - 1);
        return run.apply(selector);
    }
}
