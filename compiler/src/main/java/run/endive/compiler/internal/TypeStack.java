package run.endive.compiler.internal;

import static run.endive.wasm.types.Instruction.EMPTY_OPERANDS;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import run.endive.wasm.InvalidException;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.Instruction;
import run.endive.wasm.types.OpCode;
import run.endive.wasm.types.TypeSection;
import run.endive.wasm.types.ValType;

final class TypeStack {

    public static final Instruction FUNCTION_SCOPE =
            new Instruction(-1, OpCode.NOP, EMPTY_OPERANDS);

    private final Deque<Deque<ValType>> types = new ArrayDeque<>();
    private final Deque<Deque<ValType>> restore = new ArrayDeque<>();
    private final Map<Instruction, Integer> scopes = new HashMap<>();
    private final Map<Instruction, List<ValType>> scopeReturns = new HashMap<>();
    private final TypeSection typeSection;

    public TypeStack(TypeSection typeSection) {
        this.typeSection = typeSection;
        this.types.push(new ArrayDeque<>());
    }

    public ValType peek() {
        return types().getFirst();
    }

    public void push(ValType type) {
        types().push(type);
    }

    public void pop(ValType expected) {
        var actual = types().pop();
        if (!ValType.matches(actual, expected, typeSection)) {
            throw new IllegalArgumentException("Expected type " + expected + " <> " + actual);
        }
    }

    public void popRef() {
        var actual = types().pop();
        if (!actual.isReference()) {
            throw new IllegalArgumentException("Expected reference type <> " + actual);
        }
    }

    public void pushTypes() {
        types.push(new ArrayDeque<>(types()));
    }

    public void popTypes() {
        types.pop();
    }

    public void enterScope(Instruction scope, FunctionType scopeType) {
        scopes.put(scope, types().size());
        scopeReturns.put(scope, scopeType.returns());

        // restored stack when exiting "polymorphic" blocks after unconditional control transfer
        Deque<ValType> stack = new ArrayDeque<>(types());
        for (int i = 0; i < scopeType.params().size(); i++) {
            stack.pop();
        }
        for (ValType type : scopeType.returns()) {
            stack.push(type);
        }
        restore.push(stack);
    }

    /**
     * Enters a loop. Its parameters get the loop's declared parameter types: later iterations
     * enter through the loop's label with any subtype of them, so the types the first entry left
     * on the stack are no proof about the values the loop body sees.
     */
    public void enterLoopScope(Instruction scope, FunctionType scopeType) {
        enterScope(scope, scopeType);
        var params = scopeType.params();
        for (int i = 0; i < params.size(); i++) {
            types().pop();
        }
        for (ValType type : params) {
            types().push(type);
        }
    }

    /**
     * Leaves a scope. Its results get the scope's declared result types: values can also arrive
     * by branches to the scope's label, so the types the fallthrough path left on the stack can
     * be narrower than what the scope may produce.
     */
    public void exitScope(Instruction scope) {
        scopes.remove(scope);
        restore.pop();
        var returns = scopeReturns.remove(scope);
        if (returns != null && types().size() >= returns.size()) {
            for (int i = 0; i < returns.size(); i++) {
                types().pop();
            }
            for (ValType type : returns) {
                types().push(type);
            }
        }
    }

    public void scopeRestore() {
        types.pop();
        types.push(restore.getFirst());
    }

    public Integer scopeStackSize(Instruction scope) {
        return scopes.get(scope);
    }

    public Deque<ValType> types() {
        return types.getFirst();
    }

    public void verifyEmpty() {
        if (types.size() != 1) {
            throw new InvalidException("Bad types stack: " + types);
        }
        if (!types().isEmpty()) {
            throw new InvalidException("Types not empty: " + types());
        }
    }
}
