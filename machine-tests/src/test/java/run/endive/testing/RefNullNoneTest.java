package run.endive.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import run.endive.compiler.MachineFactoryCompiler;
import run.endive.corpus.CorpusResources;
import run.endive.runtime.Instance;
import run.endive.runtime.InterpreterMachine;
import run.endive.runtime.Machine;
import run.endive.wasm.Parser;

/** {@code (ref null none)} used where eqref, structref, arrayref and i31ref are expected. */
public class RefNullNoneTest {

    private static Stream<Arguments> engines() {
        return Stream.of(
                Arguments.of("interpreter", (Function<Instance, Machine>) InterpreterMachine::new),
                Arguments.of(
                        "compiler", (Function<Instance, Machine>) MachineFactoryCompiler::compile));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("engines")
    public void nullOfTheBottomTypeRuns(String engine, Function<Instance, Machine> machine) {
        var module = Parser.parse(CorpusResources.getResource("compiled/ref_null_none.wat.wasm"));
        var instance = Instance.builder(module).withMachineFactory(machine).build();
        assertEquals(1L, instance.export("none_eq_none").apply()[0]);
        assertEquals(0L, instance.export("struct_eq_none").apply()[0]);
        assertEquals(1L, instance.export("typed_null_eq_none").apply()[0]);
        assertEquals(4L, instance.export("pass_none").apply()[0]);
    }
}
