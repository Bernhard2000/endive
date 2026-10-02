package run.endive.compiler.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import org.objectweb.asm.Label;
import org.objectweb.asm.commons.InstructionAdapter;

/** Chooses a compact JVM encoding without changing a Wasm br_table's default semantics. */
final class SwitchEmitter {

    // Additional bounds checks have no established benefit for already small dispatch methods.
    // Reserve changing the default target for tables that alone approach the usual JIT limit.
    private static final int GUARDED_LOOKUP_MIN_TABLE_BYTES = 8000;

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
        if (tableBytes <= lookupBytes) {
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
}
