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

/**
 * One generated Wasm function with hundreds of comparison sites in a loop: signed, unsigned and
 * floating-point comparisons consumed by branches, and boolean results used as values.
 *
 * <p>The suite's other benchmarks compile small functions, where C2 inlines the comparison
 * helpers and folds their 0/1 results away, so they cannot show how the compiler lowers
 * comparisons. Here {@code sites=320} gives a method of about 7,300 bytes, just below HotSpot's
 * 8000-byte {@code HugeMethodLimit}: C2 compiles it, but the helpers compete for its inlining
 * budget ({@code DesiredMethodLimit}, also 8000). {@code sites=600} gives about 13,700 bytes,
 * which HotSpot leaves to the interpreter, where every helper call is paid in full.
 */
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode(Mode.AverageTime)
@Fork(2)
@State(Scope.Thread)
public class BenchmarkLargeMethod {

    @Param({"320", "600"})
    public int sites;

    /** default: as configured by the JVM; unsigned / branchless: the opt-in lowerings forced on. */
    @Param({"default", "unsigned-off", "branchless"})
    public String lowering;

    private ExportFunction run;

    static String wat(int sites, int iterations) {
        var wat = new StringBuilder();
        wat.append("(module (func (export \"run\") (param $a i32) (param $b i32) (result i32)");
        wat.append(" (local $i i32) (local $acc i32) (local $w i64) (local $d f64)");
        wat.append(" (local.set $w (i64.extend_i32_u (local.get $b)))");
        wat.append(" (local.set $d (f64.convert_i32_s (local.get $a)))");
        wat.append(" (loop $hot");
        for (int site = 0; site < sites; site++) {
            int k = site % 8;
            int c = site % 23 + 1;
            String cond;
            switch (k) {
                case 0:
                    cond = "(i32.lt_s (local.get $i) (local.get $a))";
                    break;
                case 1:
                    cond = "(i32.lt_u (i32.sub (local.get $acc) (local.get $i)) (local.get $b))";
                    break;
                case 2:
                    cond = "(i64.lt_s (i64.extend_i32_s (local.get $acc)) (local.get $w))";
                    break;
                case 3:
                    cond =
                            "(i32.eqz (i32.and (local.get $i) (i32.const "
                                    + (1 << (site % 16))
                                    + ")))";
                    break;
                case 4:
                    cond = "(f64.lt (local.get $d) (f64.convert_i32_s (local.get $acc)))";
                    break;
                case 5:
                    cond = "(i32.ge_u (local.get $acc) (i32.const -11))";
                    break;
                default:
                    cond = null;
                    break;
            }
            if (cond != null) {
                wat.append(" (if ").append(cond);
                wat.append(" (then (local.set $acc (i32.add (local.get $acc) (i32.const ")
                        .append(c)
                        .append("))))");
                wat.append(" (else (local.set $acc (i32.sub (local.get $acc) (i32.const ")
                        .append(c)
                        .append(")))))");
            } else if (k == 6) {
                wat.append(" (local.set $acc (i32.add (local.get $acc)");
                wat.append(
                        " (i64.eqz (i64.and (local.get $w) (i64.extend_i32_u (local.get $i))))))");
            } else {
                wat.append(" (local.set $acc (i32.xor (local.get $acc)");
                wat.append(" (i32.ne (local.get $i) (i32.const ").append(c).append("))))");
            }
        }
        wat.append(" (local.set $i (i32.add (local.get $i) (i32.const 1)))");
        wat.append(" (br_if $hot (i32.lt_s (local.get $i) (i32.const ")
                .append(iterations)
                .append("))))");
        wat.append(" (local.get $acc)))");
        return wat.toString();
    }

    @Setup
    public void setup() {
        var module = Parser.parse(Wat2Wasm.parse(wat(sites, 100)));
        var compiler = MachineFactoryCompiler.builder(module);
        if (lowering.equals("unsigned-off")) {
            compiler.withUnsignedCompareFusion(false);
        } else if (lowering.equals("branchless")) {
            compiler.withBranchlessBooleans(true);
        }
        run = Instance.builder(module).withMachineFactory(compiler.compile()).build().export("run");
        var interpreted = Instance.builder(module).build().export("run");
        if (run.apply(7, 1000)[0] != interpreted.apply(7, 1000)[0]) {
            throw new IllegalStateException("compiled result differs from the interpreter");
        }
    }

    @Benchmark
    public long[] largeMethod() {
        return run.apply(7, 1000);
    }
}
