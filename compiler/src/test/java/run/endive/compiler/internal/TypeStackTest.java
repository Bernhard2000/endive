package run.endive.compiler.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static run.endive.wasm.types.Instruction.EMPTY_OPERANDS;

import java.util.List;
import org.junit.jupiter.api.Test;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.Instruction;
import run.endive.wasm.types.OpCode;
import run.endive.wasm.types.TypeSection;
import run.endive.wasm.types.ValType;

public class TypeStackTest {

    private static final Instruction BLOCK = new Instruction(0, OpCode.BLOCK, EMPTY_OPERANDS);

    @Test
    public void exitGivesTheScopeItsDeclaredResults() {
        var stack = new TypeStack(TypeSection.builder().build());
        stack.enterScope(BLOCK, FunctionType.of(List.of(), List.of(ValType.I32)));
        stack.push(ValType.I32);
        stack.exitScope(BLOCK);
        assertEquals(List.of(ValType.I32), List.copyOf(stack.types()));
    }

    @Test
    public void exitOfAScopeNotEnteredIsRejected() {
        var stack = new TypeStack(TypeSection.builder().build());
        stack.enterScope(BLOCK, FunctionType.of(List.of(), List.of()));
        stack.exitScope(BLOCK);
        assertThrows(IllegalStateException.class, () -> stack.exitScope(BLOCK));
    }

    @Test
    public void exitWithTooFewResultsIsRejected() {
        var stack = new TypeStack(TypeSection.builder().build());
        stack.enterScope(BLOCK, FunctionType.of(List.of(), List.of(ValType.I32, ValType.I64)));
        stack.push(ValType.I32);
        assertThrows(IllegalStateException.class, () -> stack.exitScope(BLOCK));
    }
}
