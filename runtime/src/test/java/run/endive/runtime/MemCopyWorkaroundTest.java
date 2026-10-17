package run.endive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import run.endive.wasm.types.MemoryLimits;

public class MemCopyWorkaroundTest {

    @Test
    public void shouldReturnOnJavaVersion() {
        var result = MemCopyWorkaround.shouldUseMemWorkaround("1.8");
        assertTrue(result);
        result = MemCopyWorkaround.shouldUseMemWorkaround("11");
        assertTrue(result);
        result = MemCopyWorkaround.shouldUseMemWorkaround("17");
        assertTrue(result);
        result = MemCopyWorkaround.shouldUseMemWorkaround("21");
        assertFalse(result);
        result = MemCopyWorkaround.shouldUseMemWorkaround("25");
        assertFalse(result);
    }

    @Test
    public void shouldReturnOnEdgeCases() {
        var result = MemCopyWorkaround.shouldUseMemWorkaround(null);
        assertFalse(result);
        result = MemCopyWorkaround.shouldUseMemWorkaround("");
        assertFalse(result);
        result = MemCopyWorkaround.shouldUseMemWorkaround("0");
        assertFalse(result);
        result = MemCopyWorkaround.shouldUseMemWorkaround("000");
        assertTrue(result);
        result = MemCopyWorkaround.shouldUseMemWorkaround("00000");
        assertTrue(result);
        result = MemCopyWorkaround.shouldUseMemWorkaround("11-ea");
        assertTrue(result);
        result = MemCopyWorkaround.shouldUseMemWorkaround("25-ea");
        assertFalse(result);
        result = MemCopyWorkaround.shouldUseMemWorkaround("123-$%^&");
        assertFalse(result);
        result = MemCopyWorkaround.shouldUseMemWorkaround("$%^&-123");
        assertFalse(result);
    }

    @Test
    public void propertyOverridesJavaVersion() {
        assertTrue(MemCopyWorkaround.isEnabled("true", "21"));
        assertFalse(MemCopyWorkaround.isEnabled("false", "11"));
        assertTrue(MemCopyWorkaround.isEnabled(null, "17"));
        assertFalse(MemCopyWorkaround.isEnabled(null, "21"));
    }

    // Generated code calls these whenever the compiler enables the workaround, which
    // -Dendive.memCopyWorkaround=true does on any Java version.
    @Test
    public void helpersWorkOnAnyJavaVersion() {
        assertEquals(OpcodeImpl.I32_GE_U(-1, 1), MemCopyWorkaround.i32_ge_u(-1, 1));
        assertEquals(OpcodeImpl.I32_LT_U(-1, 1), MemCopyWorkaround.i32_lt_u(-1, 1));
        var memory = new ByteArrayMemory(new MemoryLimits(1));
        memory.writeI32(0, 0x01020304);
        MemCopyWorkaround.memoryCopy(8, 0, 4, memory);
        assertEquals(0x01020304, memory.readInt(8));
    }
}
