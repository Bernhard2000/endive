import run.endive.compiler.internal.Shaded;
import run.endive.runtime.MemCopyWorkaround;
import run.endive.runtime.OpcodeImpl;

/**
 * test1 of OpenJDK's TestFoldedIfsWrongReexec (JDK-8376400) with the unsigned comparisons
 * routed through the helpers Endive's generated code calls. Usage: -Dmode=0 (raw) | 1 (OpcodeImpl) | 2 (MemCopyWorkaround) | 3 (Shaded) HelperRepro
 */
public class HelperRepro {
    static int taken;
    static int MIN_VALUE = Integer.MIN_VALUE;
    static final int MODE = Integer.getInteger("mode", 0);

    static boolean geU(int a, int b) {
        switch (MODE) {
            case 0: return a + MIN_VALUE >= b + Integer.MIN_VALUE;
            case 1: return OpcodeImpl.I32_GE_U(a, b) != 0;
            case 2: return MemCopyWorkaround.i32_ge_u(a, b) != 0;
            default: return Shaded.i32_ge_u(a, b) != 0;
        }
    }

    static boolean ltU(int a, int b) {
        switch (MODE) {
            case 0: return a + MIN_VALUE < b + Integer.MIN_VALUE;
            case 1: return OpcodeImpl.I32_LT_U(a, b) != 0;
            case 2: return MemCopyWorkaround.i32_lt_u(a, b) != 0;
            default: return Shaded.i32_lt_u(a, b) != 0;
        }
    }

    public static void main(String[] args) {
        
        for (int i = 0; i < 20_000; i++) {
            test1(12);
            if (taken != 0) {
                throw new RuntimeException("branch shouldn't have been taken");
            }
            helper(16, 0);
        }
        test1(0);
        if (taken == 0) {
            throw new RuntimeException("branch should have been taken");
        }
        System.out.println("PASS");
    }

    static void test1(int i) {
        if (helper(i, 16) == 0) {
            throw new RuntimeException("never taken");
        }
        if (ltU(i, 8)) {
            taken++;
        }
        for (int j = 0; j < 10; j++) {
            for (int k = 0; k < 10; k++) {}
        }
    }

    static int helper(int i, int j) {
        if (geU(i, j)) {
            for (int k = 0; k < 100; k++) {}
            return 0;
        }
        return 1;
    }
}
