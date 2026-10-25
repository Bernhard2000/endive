package run.endive.compiler.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.Label;
import org.objectweb.asm.commons.InstructionAdapter;

/**
 * Chooses a JVM encoding for a Wasm br_table without changing its default semantics: the
 * smaller one, unless {@code tableswitch} is the faster one at a bounded cost in bytes.
 */
final class SwitchEmitter {

    // Additional bounds checks have no established benefit for already small dispatch methods.
    // Reserve changing the default target for tables that alone approach the usual JIT limit.
    private static final int GUARDED_LOOKUP_MIN_TABLE_BYTES = 8000;

    // The most bytes a switch keeps tableswitch for when it is the faster encoding. A sparse
    // table can save far more than this as lookupswitch, which keeps a method under a JVM
    // limit; a near-dense one saves little and dispatches slower. rustvm-base's interpreted
    // dispatcher (16.6 kB, 160 entries, 70 keys) ran 12.6 % slower to save 84 bytes.
    static final int MAX_BYTES_FOR_SPEED = 512;

    private SwitchEmitter() {}

    static void emit(InstructionAdapter asm, Label[] targets, Label defaultLabel) {
        Map<Label, Integer> counts = new LinkedHashMap<>();
        for (Label target : targets) {
            counts.merge(target, 1, Integer::sum);
        }
        int defaultCount = counts.getOrDefault(defaultLabel, 0);
        if (defaultCount == targets.length) {
            // Includes br_table with only a default: tableswitch would have high < low.
            asm.pop();
            asm.goTo(defaultLabel);
            return;
        }

        Label lookupDefault = defaultLabel;
        int omitted = defaultCount;
        long lookupBytes = 8L + 8L * (targets.length - omitted);
        long tableBytes = 12L + 4L * targets.length;
        if (tableBytes >= GUARDED_LOOKUP_MIN_TABLE_BYTES) {
            for (var entry : counts.entrySet()) {
                // A different default needs an unsigned bounds check. Allow for its instructions,
                // a wide goto, and the possible change in switch alignment.
                long candidateBytes = 8L + 8L * (targets.length - entry.getValue()) + 32;
                if (candidateBytes < lookupBytes) {
                    lookupDefault = entry.getKey();
                    omitted = entry.getValue();
                    lookupBytes = candidateBytes;
                }
            }
        }
        if (tableBytes <= lookupBytes
                || (tableIsFaster(
                                targets.length,
                                targets.length - omitted,
                                lookupDefault != defaultLabel)
                        && tableBytes - lookupBytes <= MAX_BYTES_FOR_SPEED)) {
            asm.tableswitch(0, targets.length - 1, defaultLabel, targets);
            return;
        }

        Label outOfRange = null;
        if (lookupDefault != defaultLabel) {
            outOfRange = new Label();
            // Wasm selectors are unsigned. Negative JVM ints and indices >= length must
            // still select the original default, rather than the most frequent target.
            asm.dup();
            asm.iflt(outOfRange);
            asm.dup();
            asm.iconst(targets.length);
            asm.ificmpge(outOfRange);
        }

        int[] keys = new int[targets.length - omitted];
        Label[] labels = new Label[keys.length];
        int next = 0;
        for (int i = 0; i < targets.length; i++) {
            if (targets[i] != lookupDefault) {
                keys[next] = i;
                labels[next++] = targets[i];
            }
        }
        asm.lookupswitch(lookupDefault, keys, labels);
        if (outOfRange != null) {
            asm.mark(outOfRange);
            asm.pop();
            asm.goTo(defaultLabel);
        }
    }

    /**
     * javac's choice between the encodings ({@code Gen.visitSwitch}): space in table entries plus
     * three times the time in compares, where a tableswitch is one bounds check and a
     * lookupswitch a compare per key (HotSpot's interpreter searches it, which is what runs a
     * method over 8000 bytes). The guarded lookup adds its two bounds checks.
     */
    static boolean tableIsFaster(int entries, int keys, boolean guarded) {
        long tableCost = 4L + entries + 3L * 3;
        long lookupCost = 3L + 2L * keys + 3L * (keys + (guarded ? 2 : 0));
        return tableCost <= lookupCost;
    }
}
