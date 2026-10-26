import java.security.MessageDigest;
import java.util.HexFormat;
import run.endive.corpus.WatGenerator;
import run.endive.wabt.Wat2Wasm;

/**
 * Runs the build-time-compiled wat2wasm (the module that exposed JDK-8376400) repeatedly in one
 * JVM on the same large inputs and checks that every run produces identical bytes. A C2
 * miscompilation shows up as an exception (originally "out of bounds memory access") or as a
 * differing hash once C2 has compiled the hot methods.
 *
 * <p>Usage: Wat2WasmStress iterations
 */
public class Wat2WasmStress {
    public static void main(String[] args) throws Exception {
        int iterations = Integer.parseInt(args[0]);
        String[] names = {"bigWat(50000,0)", "bigWat(10,15000)"};
        String[] inputs = {WatGenerator.bigWat(50_000, 0), WatGenerator.bigWat(10, 15_000)};
        String[] expected = new String[inputs.length];
        int failures = 0;
        System.out.println(
                "java.version="
                        + System.getProperty("java.version")
                        + " endive.memCopyWorkaround="
                        + System.getProperty("endive.memCopyWorkaround"));
        for (int it = 0; it < iterations; it++) {
            for (int k = 0; k < inputs.length; k++) {
                long t0 = System.nanoTime();
                String hash;
                try {
                    byte[] out = Wat2Wasm.parse(inputs[k]);
                    hash =
                            HexFormat.of()
                                    .formatHex(MessageDigest.getInstance("SHA-256").digest(out))
                                    .substring(0, 16)
                                    + " len="
                                    + out.length;
                } catch (Throwable t) {
                    hash = "EXCEPTION " + t;
                }
                long ms = (System.nanoTime() - t0) / 1_000_000;
                if (expected[k] == null && !hash.startsWith("EXCEPTION")) {
                    expected[k] = hash;
                }
                boolean ok = hash.equals(expected[k]);
                if (!ok) {
                    failures++;
                }
                System.out.printf(
                        "iter %d %s %s %d ms%s%n", it, names[k], hash, ms, ok ? "" : " MISMATCH");
            }
        }
        System.out.println("failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
