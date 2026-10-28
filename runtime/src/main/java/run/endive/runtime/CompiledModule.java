package run.endive.runtime;

import java.util.function.Function;
import run.endive.wasm.WasmModule;

/**
 * A wasm module together with a machine factory for its instances.
 */
public interface CompiledModule {
    WasmModule wasmModule();

    Function<Instance, Machine> machineFactory();
}
