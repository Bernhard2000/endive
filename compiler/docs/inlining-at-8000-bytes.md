# The 8000-byte inlining effect, measured directly (fork plan §3.7)

Status: measured 2026-10-18 on a laptop (static decision counts, not timings). Raw data:
[data/inlining-2026-10-18/](data/inlining-2026-10-18/).

## Question

The fusion work claims (JIT audit recommendation 4) that it helps large methods because C2
stops inlining the comparison helpers there: HotSpot's `DesiredMethodLimit` (8000 bytecode
bytes) bounds the *accumulated* size of a compilation, so in a method close to 8000 bytes almost
no helper can be inlined and each one stays a real call. This note counts those refusals,
upstream against the fork.

## Setup

- Module: `BenchmarkLargeMethod`'s generated function with 320 comparison sites
  (`large-320.wasm`, sha256 `cdffc735abd3…`, written by `WriteModule.java`). Its compiled
  method is 7,797 bytes upstream and 7,312 bytes on the fork, so both are below
  `HugeMethodLimit` and C2 compiles them.
- Engines: upstream main `1580f534` and the fork integration branch at `7ef91208`, each from
  its own `target/` jars (`HotLoop.java` on the classpath, ASM 9.10.1).
- `HotLoop` compiles the module with `MachineFactoryCompiler` and calls it 20,000 times.
- JVM flags: `-XX:+UnlockDiagnosticVMOptions -XX:-TieredCompilation -XX:CICompilerCount=1
  -XX:CompileCommand=quiet -XX:CompileCommand=PrintInlining,*.func_*` (C2 only, one compiler
  thread so the output is not interleaved, inlining printed for the generated functions only).
- JDKs: OpenJDK 21.0.12.1 (openSUSE build) and Temurin 17.0.7.
- `count_inlining.py` counts the decisions for the function's own (depth-0) call sites into
  `OpcodeImpl`, the generated `Shaded` copy and the JDK helpers. The function is compiled twice
  (OSR and normal entry), so every site appears twice.

## Result

| JDK | engine | helper call sites | refused: `size > DesiredMethodLimit` | inlined | intrinsic |
| --- | --- | ---: | ---: | ---: | ---: |
| 21 | upstream | 806 | **779** | 27 | 0 |
| 21 | fork | 403 | **105** | 138 | 160 |
| 17 | upstream | 806 | **775** | 31 | 0 |
| 17 | fork | 403 | **327** | 76 | 0 |

- Upstream, C2 refuses 97 % of the helper calls in this method for exactly the reason the audit
  predicted. Each refused site is a static call plus the 0/1 materialization inside the helper.
- The fork halves the number of helper call sites (fusion removes the comparison helpers at
  branch sites, the narrowing pass removes `f64.convert_i32_s`), and on Java 21 most of the rest
  are intrinsics (`Integer.compareUnsigned` from unsigned compare fusion, which
  `MachineFactoryCompiler` enables there) or fit the budget again. The remaining refusals are
  value-context `i32.ne`/`i64.eqz` (what branchless booleans would remove) and
  `Integer.toUnsignedLong`.
- On Java 17 unsigned fusion is off (JDK-8376400), so the `Shaded.i32_lt_u`/`i32_ge_u` calls
  stay, and with them most refusals. Fusion of the signed, `i64` and floating-point comparisons
  still halves the call sites.

This is the mechanism behind the steady-state gains measured on the split workloads, whose
methods sit just under 8000 bytes by construction. It also predicts that unsplit methods far
below the limit gain little, which matches the JMH suite's "no change".

## Reproduce

```sh
D=compiler/docs/data/inlining-2026-10-18
javac --release 17 -cp jmh/target/benchmarks.jar -d out $D/WriteModule.java
java -cp jmh/target/benchmarks.jar:out WriteModule 320 100 large-320.wasm
javac --release 17 -cp "<engine jars>" -d out $D/HotLoop.java
java -XX:+UnlockDiagnosticVMOptions -XX:-TieredCompilation -XX:CICompilerCount=1 \
  -XX:CompileCommand=quiet -XX:CompileCommand=PrintInlining,'*.func_*' \
  -cp "out:<engine jars>" HotLoop large-320.wasm 20000 > printinlining.txt
python3 $D/count_inlining.py printinlining.txt
```
