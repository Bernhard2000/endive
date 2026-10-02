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

/** Synthetic tuple and route-dispatch costs; use real split modules for workload acceptance. */
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(2)
public class BenchmarkSplitFunctionBoundaries {

    @State(Scope.Thread)
    public static class Tuples {
        @Param({"2", "8", "32"})
        public int results;

        private ExportFunction run;

        @Setup
        public void setup() {
            var wat = new StringBuilder("(module (func $leaf (param i32) (result i32");
            wat.append(" i64".repeat(results - 1)).append(") local.get 0");
            for (int i = 1; i < results; i++) {
                wat.append(" local.get 0 i64.extend_i32_s i64.const ").append(i).append(" i64.add");
            }
            wat.append(")");
            for (int depth = 0; depth < 4; depth++) {
                wat.append(" (func $f").append(depth);
                if (depth == 3) {
                    wat.append(" (export \"run\")");
                }
                wat.append(" (param i32) (result i32").append(" i64".repeat(results - 1));
                wat.append(") local.get 0 call ").append(depth == 0 ? "$leaf" : "$f" + (depth - 1));
                wat.append(")");
            }
            wat.append(")");
            run = compile(wat.toString());
            long[] values = run.apply(42);
            if (values.length != results || values[results - 1] != 42 + results - 1) {
                throw new IllegalStateException("Incorrect tuple benchmark result");
            }
        }
    }

    @State(Scope.Thread)
    public static class Dispatch {
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
            run = compile(wat.toString());
            if (run.apply(0)[0] != 11 || run.apply(1)[0] != 22 || run.apply(-1)[0] != 33) {
                throw new IllegalStateException("Incorrect dispatch benchmark result");
            }
        }
    }

    private static ExportFunction compile(String wat) {
        return Instance.builder(Parser.parse(Wat2Wasm.parse(wat)))
                .withMachineFactory(MachineFactoryCompiler::compile)
                .build()
                .export("run");
    }

    @Benchmark
    public long[] tupleForwarding(Tuples tuples) {
        return tuples.run.apply(42);
    }

    @Benchmark
    public long[] sparseDispatch(Dispatch dispatch) {
        dispatch.selector = (dispatch.selector + 1) & (dispatch.entries - 1);
        return dispatch.run.apply(dispatch.selector);
    }
}
