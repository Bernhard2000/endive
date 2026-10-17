import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.runtime.ExportFunction;
import run.endive.runtime.ImportValues;
import run.endive.runtime.Instance;
import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.ByteBufferMemory;
import run.endive.runtime.Machine;
import run.endive.runtime.Memory;
import run.endive.wasm.types.MemoryLimits;
import run.endive.wasi.WasiOptions;
import run.endive.wasi.WasiPreview1;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/**
 * Live Endive (WASM->JVM AOT) execution harness for a standalone WASI module.
 * Measures: AOT compile time (where giant methods fall to the interpreter) and
 * _start wall time, base vs split. Args: <module.wasm> <label> [warmup] [reps]
 * [workload args...]. Emits a JSON line.
 *
 * reps may be the literal "auto" (R16.3): keep executing until the
 * sliding-window (n=5) coefficient of variation drops below 1% or a hard
 * cap of 30 measured reps, then report the converged window median plus
 * the pre-convergence samples separately (they are the warmup curve).
 * Numeric reps behaves exactly as before, for comparability with the
 * 2026-07-16 campaign data.
 */
public class EndiveRun {
    private static final int AUTO_WINDOW = 5;
    private static final double AUTO_CV = 0.01;
    private static final int AUTO_CAP = 30;

    private static final class Preopen {
        final String guest;
        final String host;
        Preopen(String guest, String host) { this.guest = guest; this.host = host; }
    }

    private static final class RunTimings {
        final double totalMs;
        final double instantiateMs;
        final double startMs;
        RunTimings(double totalMs, double instantiateMs, double startMs) {
            this.totalMs = totalMs;
            this.instantiateMs = instantiateMs;
            this.startMs = startMs;
        }
    }

    @Name("Endive.BenchmarkExecution")
    @Label("Endive benchmark execution")
    @Category({"Endive", "Benchmark"})
    private static final class BenchmarkExecution extends Event {
        @Label("Benchmark label")
        String label;

        @Label("Phase")
        String phase;

        @Label("Execution index")
        int index;

        BenchmarkExecution(String label, String phase, int index) {
            this.label = label;
            this.phase = phase;
            this.index = index;
        }
    }

    public static void main(String[] argv) throws Exception {
        String path = argv[0];
        String label = argv[1];
        int warmup = argv.length > 2 ? Integer.parseInt(argv[2]) : 2;
        boolean autoReps = argv.length > 3 && argv[3].equals("auto");
        int reps = argv.length > 3 && !autoReps ? Integer.parseInt(argv[3]) : 5;
        List<Preopen> preopens = configuredPreopens();
        byte[] bytes = java.nio.file.Files.readAllBytes(new File(path).toPath());
        checkSnapshotManifest(preopens, bytes);
        List<String> workloadArgs = new java.util.ArrayList<>();
        // CPython derives its prefix (and finds /lib/python3.x) from argv[0];
        // override via ENDIVE_ARGV0=python.wasm when running python modules.
        String argv0 = System.getenv("ENDIVE_ARGV0");
        workloadArgs.add(argv0 != null ? argv0 : path);
        if (argv.length > 4) {
            workloadArgs.addAll(java.util.Arrays.asList(argv).subList(4, argv.length));
        }

        // Keep parsing and machine compilation separate, while retaining the
        // historical compile_ms aggregate used by existing result consumers.
        long p0 = System.nanoTime();
        WasmModule module = Parser.parse(bytes);
        double parseMs = (System.nanoTime() - p0) / 1e6;
        long c0 = System.nanoTime();
        Function<Instance, Machine> factory = MachineFactoryCompiler.compile(module);
        double machineCompileMs = (System.nanoTime() - c0) / 1e6;
        double compileMs = parseMs + machineCompileMs;

        double[] allSamples;
        double[] measuredSamples;
        List<Double> instantiateSeries = new java.util.ArrayList<>();
        List<Double> startSeries = new java.util.ArrayList<>();
        int preconverged = 0;
        boolean jfrMarkers = "1".equals(System.getenv("ENDIVE_JFR_MARKERS"));
        if (autoReps) {
            java.util.List<Double> series = new java.util.ArrayList<>();
            while (series.size() < warmup + AUTO_CAP) {
                RunTimings timing = runMarked(module, factory, workloadArgs, preopens,
                    jfrMarkers, label,
                    series.size() < warmup ? "warmup" : "measured",
                    series.size() - warmup);
                series.add(timing.totalMs);
                instantiateSeries.add(timing.instantiateMs);
                startSeries.add(timing.startMs);
                int measured = series.size() - warmup;
                if (measured >= AUTO_WINDOW
                        && slidingCv(series, AUTO_WINDOW) < AUTO_CV) {
                    break;
                }
            }
            allSamples = new double[series.size()];
            for (int i = 0; i < series.size(); i++) allSamples[i] = series.get(i);
            int windowStart = series.size() - Math.min(
                AUTO_WINDOW, series.size() - warmup);
            measuredSamples = java.util.Arrays.copyOfRange(
                allSamples, windowStart, allSamples.length);
            preconverged = windowStart - warmup;
        } else {
            allSamples = new double[warmup + reps];
            measuredSamples = new double[reps];
            for (int i = 0; i < warmup + reps; i++) {
                RunTimings timing = runMarked(module, factory, workloadArgs, preopens,
                    jfrMarkers, label,
                    i < warmup ? "warmup" : "measured", i - warmup);
                double ms = timing.totalMs;
                allSamples[i] = ms;
                instantiateSeries.add(timing.instantiateMs);
                startSeries.add(timing.startMs);
                if (i >= warmup) measuredSamples[i - warmup] = ms;
            }
        }
        // PER_REP=1 emits one row per raw sample (warmup included) so warmup
        // curves can be plotted externally; the summary line below is unchanged.
        if ("1".equals(System.getenv("PER_REP"))) {
            for (int i = 0; i < allSamples.length; i++) {
                System.out.println(String.format(java.util.Locale.ROOT,
                    "REP,%s,%d,%d,%.3f", label, i, i < warmup ? 1 : 0, allSamples[i]));
            }
        }
        double[] sorted = measuredSamples.clone();
        java.util.Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];
        double min = sorted[0];
        double first = allSamples[0];
        double last = allSamples[allSamples.length - 1];
        double warmupPenalty = 0.0;
        int penaltySamples = warmup > 0 ? warmup : 1;
        for (int i = 0; i < penaltySamples; i++) {
            warmupPenalty += Math.max(0.0, allSamples[i] - median);
        }

        String autoFields = autoReps
            ? String.format(java.util.Locale.ROOT,
                "\"reps_mode\":\"auto\",\"reps_used\":%d," +
                "\"preconverged_reps\":%d,",
                allSamples.length - warmup, preconverged)
            : "";
        System.out.println(String.format(java.util.Locale.ROOT,
            "{\"label\":\"%s\",\"bytes\":%d,\"parse_ms\":%.3f," +
            "\"machine_compile_ms\":%.3f,\"compile_ms\":%.3f," +
            "\"first_exec_ms\":%.3f,\"last_exec_ms\":%.3f," +
            "\"warmup_penalty_ms\":%.3f,\"exec_ms_median\":%.3f," +
            "\"exec_ms_min\":%.3f,\"instantiate_ms\":%s,\"start_ms\":%s," +
            "%s\"memory_backend\":\"%s\",\"rss_hwm_kb\":%d,\"all_exec_ms\":%s}",
            label, bytes.length, parseMs, machineCompileMs, compileMs, first,
            last, warmupPenalty, median, min,
            java.util.Arrays.toString(toPrimitive(instantiateSeries)),
            java.util.Arrays.toString(toPrimitive(startSeries)),
            autoFields, memoryBackend(), readRssHwmKb(),
            java.util.Arrays.toString(allSamples)));
    }

    /**
     * SS9a: Endive's memory backend, a deployment dimension rather than an
     * engine change. {@code -Dendive.memory=bytebuffer} (Endive's own default,
     * {@code ByteBufferMemory}) or {@code bytearray} ({@code ByteArrayMemory},
     * reads through a byte-array VarHandle). Every Wasm memory access is an
     * {@code invokestatic} into a wrapper whose transitive inline charge
     * differs by backend, which moves C2's inline budget directly.
     */
    static String memoryBackend() {
        String backend = System.getProperty("endive.memory", "bytebuffer");
        if (!backend.equals("bytebuffer") && !backend.equals("bytearray")) {
            throw new IllegalArgumentException(
                "-Dendive.memory must be bytebuffer or bytearray, got " + backend);
        }
        return backend;
    }

    static Function<MemoryLimits, Memory> memoryFactory() {
        return memoryBackend().equals("bytearray") ? ByteArrayMemory::new : ByteBufferMemory::new;
    }

    private static double[] toPrimitive(List<Double> values) {
        double[] result = new double[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private static RunTimings runMarked(
            WasmModule module,
            Function<Instance, Machine> factory,
            List<String> workloadArgs,
            List<Preopen> preopens,
            boolean enabled,
            String label,
            String phase,
            int index) {
        if (EXEC_MARKERS) {
            execMark("begin", label, phase, index);
        }
        try {
            return runJfrMarked(module, factory, workloadArgs, preopens, enabled,
                label, phase, index);
        } finally {
            if (EXEC_MARKERS) {
                execMark("end", label, phase, index);
            }
        }
    }

    // ENDIVE_EXEC_MARKERS=1 (SS12): one stdout line before and after every
    // execution, stamped with JVM uptime in milliseconds -- the clock
    // -XX:+PrintCompilation's first column and LogCompilation's `stamp`
    // (seconds) use -- so a compile log can be cut at the warmup boundary.
    private static final boolean EXEC_MARKERS =
        "1".equals(System.getenv("ENDIVE_EXEC_MARKERS"));

    private static void execMark(String edge, String label, String phase, int index) {
        long uptime = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        System.out.println("EXEC_MARK " + edge + " " + label + " " + phase + " "
            + index + " " + uptime);
        System.out.flush();
    }

    private static RunTimings runJfrMarked(
            WasmModule module,
            Function<Instance, Machine> factory,
            List<String> workloadArgs,
            List<Preopen> preopens,
            boolean enabled,
            String label,
            String phase,
            int index) {
        if (!enabled) {
            return runOnce(module, factory, workloadArgs, preopens);
        }
        var event = new BenchmarkExecution(label, phase, index);
        event.begin();
        try {
            return runOnce(module, factory, workloadArgs, preopens);
        } finally {
            event.end();
            event.commit();
        }
    }

    /** Coefficient of variation of the last {@code window} samples. */
    private static double slidingCv(java.util.List<Double> series, int window) {
        double mean = 0.0;
        for (int i = series.size() - window; i < series.size(); i++) {
            mean += series.get(i);
        }
        mean /= window;
        if (mean <= 0.0) return Double.MAX_VALUE;
        double variance = 0.0;
        for (int i = series.size() - window; i < series.size(); i++) {
            double d = series.get(i) - mean;
            variance += d * d;
        }
        return Math.sqrt(variance / window) / mean;
    }

    /**
     * Peak resident set (VmHWM) of this JVM in kB, read from /proc/self/status
     * just before result emission so it covers parse, compile, and every
     * execution. Returns -1 where /proc is unavailable (non-Linux).
     */
    private static long readRssHwmKb() {
        try {
            for (String line : java.nio.file.Files.readAllLines(
                    java.nio.file.Path.of("/proc/self/status"))) {
                if (line.startsWith("VmHWM:")) {
                    return Long.parseLong(
                        line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return -1;
    }

    private static RunTimings runOnce(
            WasmModule module,
            Function<Instance, Machine> factory,
            List<String> workloadArgs,
            List<Preopen> preopens) {
        long wholeStart = System.nanoTime();
        WasiOptions.Builder optionsBuilder = WasiOptions.builder()
            .inheritSystem()
            .withArguments(workloadArgs);
        for (Preopen preopen : preopens)
            optionsBuilder.withDirectory(preopen.guest, java.nio.file.Path.of(preopen.host));
        WasiOptions options = optionsBuilder.build();
        WasiPreview1 wasi = WasiPreview1.builder().withOptions(options).build();
        ImportValues imports = ImportValues.builder()
            .addFunction(wasi.toHostFunctions()).build();
        long instantiateStart = System.nanoTime();
        Instance instance;
        try {
            instance = Instance.builder(module)
                // Endive otherwise invokes an exported _start during build().
                // Keep that guest work in the explicit phase below so the
                // instantiate and start series measure disjoint intervals.
                .withStart(false)
                .withMachineFactory(factory)
                .withMemoryFactory(memoryFactory())
                .withImportValues(imports)
                .build();
        } catch (Throwable t) {
            throw new RuntimeException("module instantiation failed", t);
        }
        double instantiateMs = (System.nanoTime() - instantiateStart) / 1e6;
        ExportFunction start = instance.export("_start");
        if (start == null) throw new RuntimeException("_start not found");
        long startStart = System.nanoTime();
        try {
            start.apply();
        } catch (Throwable t) {
            // WASI _start terminates via WasiExitException; treat exit(0) as
            // success. This wasi-sdk _start reaches the `unreachable` epilogue
            // after exit(), surfacing as a TrapException AFTER the workload has
            // fully run (stdout already emitted). Correctness is validated out
            // of band (wasmtime), so treat that terminal trap as normal exit.
            String n = t.getClass().getName();
            if (n.contains("WasiExitException")) {
                try {
                    int code = (int) t.getClass().getMethod("exitCode").invoke(t);
                    if (code != 0) throw new RuntimeException("exit code " + code, t);
                } catch (ReflectiveOperationException e) { /* normal exit */ }
            } else if (n.contains("TrapException")) {
                /* terminal unreachable after exit(); workload completed */
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else {
                throw new RuntimeException(t);
            }
        }
        double startMs = (System.nanoTime() - startStart) / 1e6;
        return new RunTimings((System.nanoTime() - wholeStart) / 1e6,
                              instantiateMs, startMs);
    }

    private static List<Preopen> configuredPreopens() {
        List<Preopen> result = new java.util.ArrayList<>();
        String many = System.getenv("ENDIVE_MOUNTS");
        if (many != null && !many.isBlank()) {
            for (String mount : many.split(";")) addPreopen(result, mount);
        } else {
            String one = System.getenv("ENDIVE_MOUNT");
            if (one != null && !one.isBlank()) addPreopen(result, one);
        }
        return result;
    }

    private static void addPreopen(List<Preopen> into, String mount) {
        int separator = mount.indexOf('=');
        if (separator <= 0 || separator == mount.length() - 1)
            throw new IllegalArgumentException("preopen must be <guest>=<host>: " + mount);
        String guest = mount.substring(0, separator);
        String host = mount.substring(separator + 1);
        if (!guest.startsWith("/"))
            throw new IllegalArgumentException("preopen guest path must be absolute: " + guest);
        into.add(new Preopen(guest, host));
    }

    private static void checkSnapshotManifest(List<Preopen> configured, byte[] moduleBytes)
            throws IOException {
        String manifestPath = System.getenv("ENDIVE_SNAPSHOT_MANIFEST");
        if (manifestPath == null || manifestPath.isBlank()) {
            // A raw split cell names only its campaign manifest; it must still
            // be the module that manifest's gates passed on.
            String rawVariant = System.getenv("ENDIVE_VARIANT_MANIFEST");
            if (rawVariant != null && !rawVariant.isBlank()) {
                String variant = java.nio.file.Files.readString(java.nio.file.Path.of(rawVariant));
                if (!sha256(moduleBytes).equals(artifactHash(variant, "output")))
                    throw new IllegalArgumentException(
                        "split manifest output hash does not match module");
            }
            return;
        }
        String manifest = java.nio.file.Files.readString(java.nio.file.Path.of(manifestPath));
        if (!Pattern.compile("\\\"schema\\\"\\s*:\\s*\\\"wizer-snapshot-v1\\\"")
                .matcher(manifest).find())
            throw new IllegalArgumentException(manifestPath + " is not a wizer-snapshot-v1 manifest");
        Matcher output = Pattern.compile("\\\"output\\\"\\s*:\\s*\\{(.*?)\\}",
                                         Pattern.DOTALL).matcher(manifest);
        if (!output.find())
            throw new IllegalArgumentException("snapshot manifest has no output record");
        Matcher outputHash = Pattern.compile("\\\"sha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"")
                                    .matcher(output.group(1));
        if (!outputHash.find())
            throw new IllegalArgumentException("snapshot manifest output has no sha256");
        String snapshotOutputHash = outputHash.group(1);
        String moduleHash = sha256(moduleBytes);
        String variantPath = System.getenv("ENDIVE_VARIANT_MANIFEST");
        boolean splitFromSnapshot = variantPath != null && !variantPath.isBlank();
        if (splitFromSnapshot) {
            String variant = java.nio.file.Files.readString(java.nio.file.Path.of(variantPath));
            if (!Pattern.compile("\\\"schema\\\"\\s*:\\s*\\\"wasm-splitting-campaign-v1\\\"")
                    .matcher(variant).find())
                throw new IllegalArgumentException(variantPath +
                    " is not a wasm-splitting-campaign-v1 manifest");
            String outputArtifact = artifactHash(variant, "output");
            if (!moduleHash.equals(outputArtifact))
                throw new IllegalArgumentException("split manifest output hash does not match module");
            String snapshotArtifact = artifactHash(variant, "snapshot_manifest");
            if (!snapshotArtifact.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("split manifest snapshot artifact has no sha256");
            String snapshotHash = sha256(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String inputHash = moduleHash(variant, "base");
            if (!inputHash.equals(snapshotOutputHash))
                throw new IllegalArgumentException(
                    "split manifest input does not match the supplied Wizer snapshot");
            Matcher controlHash = Pattern.compile(
                "\\\"snapshot_manifest_sha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"")
                .matcher(variant);
            if (!controlHash.find() || !snapshotHash.equals(controlHash.group(1)))
                throw new IllegalArgumentException(
                    "split manifest snapshot control hash does not match Wizer manifest");
        } else if (!snapshotOutputHash.equals(moduleHash)) {
            throw new IllegalArgumentException("snapshot manifest output hash does not match module");
        }
        Matcher block = Pattern.compile("\"preopens\"\\s*:\\s*\\[(.*?)\\]",
                                        Pattern.DOTALL).matcher(manifest);
        if (!block.find()) throw new IllegalArgumentException("snapshot manifest has no preopens list");
        Matcher guests = Pattern.compile("\"guest\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
                                 .matcher(block.group(1));
        java.util.ArrayList<String> expected = new java.util.ArrayList<>();
        while (guests.find()) expected.add(guests.group(1).replace("\\\"", "\"")
                                                        .replace("\\\\", "\\"));
        long entries = block.group(1).chars().filter(c -> c == '{').count();
        if (entries != expected.size())
            throw new IllegalArgumentException("snapshot manifest has an invalid preopens list");
        java.util.ArrayList<String> actual = new java.util.ArrayList<>();
        for (Preopen preopen : configured) actual.add(preopen.guest);
        if (!expected.equals(actual))
            throw new IllegalArgumentException("Wizer preopens differ from " + manifestPath +
                ": expected " + expected + ", got " + actual);
    }

    /**
     * The flat record {@code outer.inner} of a campaign manifest. A lazy
     * {@code \\{(.*?)\\}} over the outer object stops at the first nested
     * closing brace, so the inner record is matched from the outer key onward
     * instead; campaign-manifest records are flat {path, sha256, ...} objects.
     */
    private static String nestedRecord(String manifest, String outer, String inner) {
        Matcher start = Pattern.compile("\"" + Pattern.quote(outer) + "\"\\s*:\\s*\\{")
            .matcher(manifest);
        if (!start.find())
            throw new IllegalArgumentException("split manifest has no " + outer);
        Matcher block = Pattern.compile("\"" + Pattern.quote(inner) +
            "\"\\s*:\\s*\\{([^{}]*)\\}", Pattern.DOTALL).matcher(manifest);
        if (!block.find(start.end()))
            throw new IllegalArgumentException("split manifest has no " + outer + "." + inner);
        return block.group(1);
    }

    private static String artifactHash(String manifest, String artifact) {
        String record = nestedRecord(manifest, "artifacts", artifact);
        Matcher hash = Pattern.compile("\\\"sha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"")
            .matcher(record);
        if (!hash.find())
            throw new IllegalArgumentException("split manifest " + artifact + " has no SHA-256");
        return hash.group(1);
    }

    private static String moduleHash(String manifest, String module) {
        String record = nestedRecord(manifest, "modules", module);
        Matcher hash = Pattern.compile("\\\"sha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"")
                              .matcher(record);
        if (!hash.find())
            throw new IllegalArgumentException("split manifest " + module + " module has no sha256");
        return hash.group(1);
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder(64);
            for (byte item : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", item));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
