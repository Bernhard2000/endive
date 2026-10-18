package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import run.endive.corpus.CorpusResources;
import run.endive.wasm.Parser;
import run.endive.wasm.types.ExternalType;

/**
 * The analyzer runs {@link NarrowingPeephole} and branch-condition fusion as instructions are
 * appended ({@link WasmAnalyzer.PeepholeList}). That must produce exactly what the two passes
 * produce over the finished list, for every function of these modules.
 */
public class PeepholeListTest {

    private static final String[] MODULES = {
        "compiled/main.go.wasm",
        "compiled/basic.dotnet.wasm",
        "compiled/branch_conditions.wat.wasm",
        "compiled/branch_conditions_const.wat.wasm",
        "compiled/narrowing.wat.wasm",
        "compiled/ref_conditions.wat.wasm",
        "compiled/ref_eq.wat.wasm",
        "compiled/bool_values.wat.wasm",
    };

    @Test
    public void incrementalEqualsBatchPasses() {
        for (var name : MODULES) {
            var module = Parser.parse(CorpusResources.getResource(name));
            int imports = module.importSection().count(ExternalType.FUNCTION);
            int count = module.functionSection().functionCount();
            for (boolean unsigned : new boolean[] {false, true}) {
                var analyzer = new WasmAnalyzer(module, unsigned, false);
                var rawAnalyzer = new WasmAnalyzer(module, unsigned, false).withoutPeepholes();
                for (int i = 0; i < count; i++) {
                    int funcId = imports + i;
                    var incremental = analyzer.analyze(funcId).instructions();
                    var raw = rawAnalyzer.analyze(funcId).instructions();
                    var batch =
                            WasmAnalyzer.fuseBranchConditions(
                                    NarrowingPeephole.apply(raw), module.typeSection(), unsigned);
                    assertEquals(
                            batch.stream().map(Object::toString).collect(Collectors.toList()),
                            incremental.stream().map(Object::toString).collect(Collectors.toList()),
                            name + " function " + funcId);
                }
            }
        }
    }
}
