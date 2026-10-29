package run.endive.compiler.internal;

import static java.util.stream.Collectors.toSet;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;
import run.endive.wasm.WasmModule;
import run.endive.wasm.types.ExternalType;
import run.endive.wasm.types.OpCode;

/**
 * Computes the functions whose bodies the interpreter may run in a compiled module.
 */
public final class InterpretableFunctions {

    private InterpretableFunctions() {}

    /**
     * Returns the interpreted functions plus every function they reach through tail calls, or
     * every defined function if an indirect tail call makes the targets unknown.
     *
     * @param interpretedFunctions function indices in the module's function index space
     */
    public static Set<Integer> of(WasmModule module, Set<Integer> interpretedFunctions) {
        int functionImports = module.importSection().count(ExternalType.FUNCTION);
        var result = new HashSet<Integer>();
        var pending = new ArrayDeque<>(interpretedFunctions);
        while (!pending.isEmpty()) {
            int funcId = pending.pop();
            if (funcId < functionImports || !result.add(funcId)) {
                continue;
            }
            var body = module.codeSection().getFunctionBody(funcId - functionImports);
            for (var ins : body.instructions()) {
                if (ins.opcode() == OpCode.RETURN_CALL) {
                    pending.push((int) ins.operand(0));
                } else if (ins.opcode() == OpCode.RETURN_CALL_INDIRECT
                        || ins.opcode() == OpCode.RETURN_CALL_REF) {
                    int functions = module.functionSection().functionCount();
                    return IntStream.range(functionImports, functionImports + functions)
                            .boxed()
                            .collect(toSet());
                }
            }
        }
        return result;
    }
}
