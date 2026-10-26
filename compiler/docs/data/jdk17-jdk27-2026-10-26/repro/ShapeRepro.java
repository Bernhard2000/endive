import run.endive.runtime.OpcodeImpl;

/** Shapes of the JDK-8376400 trigger built from Endive's unprotected OpcodeImpl helpers. -Dshape=N */
public class ShapeRepro {
    static final int SHAPE = Integer.getInteger("shape", 0);
    static int taken;
    static int MIN_VALUE = Integer.MIN_VALUE;

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

    static boolean ltU(int i, int c) {
        switch (SHAPE) {
            case 0: return i + MIN_VALUE < c + Integer.MIN_VALUE;
            case 1: case 3: return Integer.compareUnsigned(i, c) < 0;
            case 2: case 4: return OpcodeImpl.I32_LT_U(i, c) != 0;
            case 5: return i + MIN_VALUE < c + Integer.MIN_VALUE;
            default: return OpcodeImpl.I32_LT_U(i, c) == 1;
        }
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
        boolean ge;
        switch (SHAPE) {
            case 0: ge = i + MIN_VALUE >= j + Integer.MIN_VALUE; break;
            case 1: case 5: ge = Integer.compareUnsigned(i, j) >= 0; break;
            case 2: ge = OpcodeImpl.I32_GE_U(i, j) != 0; break;
            case 3: ge = i + MIN_VALUE >= j + Integer.MIN_VALUE; break;
            case 4: ge = i + MIN_VALUE >= j + Integer.MIN_VALUE; break;
            default: ge = OpcodeImpl.I32_GE_U(i, j) == 1;
        }
        if (ge) {
            for (int k = 0; k < 100; k++) {}
            return 0;
        }
        return 1;
    }
}
