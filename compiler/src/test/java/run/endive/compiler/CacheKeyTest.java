package run.endive.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.wasm.Parser;
import run.endive.wasm.WasmModule;

/**
 * The cache key must keep the {@code algo:digest} form that caches such as the directory cache
 * validate (a padded Base64 digest), and tell apart every setting that changes the generated
 * code.
 */
public class CacheKeyTest {

    // the digest form DirectoryCache accepts
    private static final String DIGEST = "^[A-Za-z0-9+_\\-/]+=$";

    private static WasmModule module() {
        return Parser.parse(CorpusResources.getResource("compiled/count_vowels.rs.wasm"));
    }

    private static String key(boolean unsignedCompareFusion, boolean branchlessBooleans) {
        return MachineFactoryCompiler.builder(module())
                .withUnsignedCompareFusion(unsignedCompareFusion)
                .withBranchlessBooleans(branchlessBooleans)
                .cacheKey();
    }

    @Test
    public void keyIsAlgorithmAndPaddedBase64Digest() {
        var key = key(true, true);
        int colon = key.indexOf(':');
        assertEquals(MachineFactoryCompiler.CACHE_KEY_ALGORITHM, key.substring(0, colon));
        assertTrue(key.substring(colon + 1).matches(DIGEST), key);
    }

    @Test
    public void keyIsStable() {
        assertEquals(key(false, true), key(false, true));
    }

    @Test
    public void everyOptionCombinationHasItsOwnKey() {
        Set<String> keys = new HashSet<>();
        for (boolean unsigned : new boolean[] {false, true}) {
            for (boolean branchless : new boolean[] {false, true}) {
                keys.add(key(unsigned, branchless));
            }
        }
        assertEquals(4, keys.size());
    }

    @Test
    public void codeGenerationSettingsChangeTheKey() {
        var module = module();
        var plain = MachineFactoryCompiler.builder(module).cacheKey();
        assertNotEquals(module.digest(), plain);
        assertNotEquals(
                plain,
                MachineFactoryCompiler.builder(module)
                        .withInterpreterFallback(InterpreterFallback.FAIL)
                        .cacheKey());
        assertNotEquals(
                plain,
                MachineFactoryCompiler.builder(module).withMaxFunctionsPerClass(7).cacheKey());
        assertNotEquals(
                plain, MachineFactoryCompiler.builder(module).withClassName("a.B").cacheKey());
        assertNotEquals(
                plain,
                MachineFactoryCompiler.builder(module)
                        .withInterpretedFunctions(Set.of(1))
                        .cacheKey());
        assertEquals(
                MachineFactoryCompiler.builder(module)
                        .withInterpretedFunctions(Set.of(1, 2, 3))
                        .cacheKey(),
                MachineFactoryCompiler.builder(module)
                        .withInterpretedFunctions(new LinkedHashSet<>(List.of(3, 2, 1)))
                        .cacheKey());
    }
}
