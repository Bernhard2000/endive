package run.endive.wasm;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class Encoding {

    private Encoding() {}

    static int readInt(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            throw new MalformedException("length out of bounds");
        }
        return buffer.getInt();
    }

    static byte readByte(ByteBuffer buffer) {
        if (!buffer.hasRemaining()) {
            throw new MalformedException("length out of bounds");
        }
        return buffer.get();
    }

    static void readBytes(ByteBuffer buffer, byte[] dest) {
        if (buffer.remaining() < dest.length) {
            throw new MalformedException("length out of bounds");
        }
        buffer.get(dest);
    }

    /**
     * Read an unsigned I32 from the buffer. We can't fit an unsigned 32bit int
     * into a java int, so we must use a long.
     * See <a href="https://www.w3.org/TR/wasm-core-1/#integers">2.2.2. Integers</a> of the WebAssembly Core Specification.
     *
     * @param buffer the byte buffer
     * @return the resulting long
     */
    public static long readVarUInt32(ByteBuffer buffer) {
        return readLeb128(buffer, 32, false);
    }

    /**
     * Read a signed I32 from the buffer. We can't fit an unsigned 32bit int into a java int, so we must use a long to use the same type as unsigned.
     * See <a href="https://www.w3.org/TR/wasm-core-1/#integers">2.2.2. Integers</a> of the WebAssembly Core Specification.
     *
     * @param buffer the byte buffer
     * @return the resulting long
     */
    public static long readVarSInt32(ByteBuffer buffer) {
        return readLeb128(buffer, 32, true);
    }

    /**
     * Read a signed I64 from the buffer which fits neatly into a long.
     * See <a href="https://www.w3.org/TR/wasm-core-1/#integers">2.2.2. Integers</a> of the WebAssembly Core Specification.
     *
     * @param buffer the byte buffer
     * @return the resulting long
     */
    public static long readVarSInt64(ByteBuffer buffer) {
        return readLeb128(buffer, 64, true);
    }

    /**
     * Read a F64 from the buffer which fits neatly into a long.
     * See <a href="https://www.w3.org/TR/wasm-core-1/#floating-point">2.2.3. Floating-Point</a> of the WebAssembly Core Specification.
     *
     * @param buffer the byte buffer
     * @return the resulting long
     */
    public static long readFloat64(ByteBuffer buffer) {
        return buffer.getLong();
    }

    /**
     * Read a F32 from the buffer which fits neatly into a long.
     * See <a href="https://www.w3.org/TR/wasm-core-1/#floating-point">2.2.3. Floating-Point</a> of the WebAssembly Core Specification.
     *
     * @param buffer the byte buffer
     * @return the resulting long
     */
    public static long readFloat32(ByteBuffer buffer) {
        return readInt(buffer);
    }

    /**
     * Read a symbol name from the buffer as UTF-8 String.
     * See <a href="https://www.w3.org/TR/wasm-core-1/#names%E2%91%A0">2.2.4. Names</a> of the WebAssembly Core Specification.
     *
     * @param buffer the byte buffer
     * @return the resulting long
     */
    public static String readName(ByteBuffer buffer) {
        return readName(buffer, true);
    }

    /**
     * Read a symbol name from the buffer as UTF-8 String.
     * See <a href="https://www.w3.org/TR/wasm-core-1/#names">2.2.4. Names</a> of the WebAssembly Core Specification.
     *
     * @param buffer the byte buffer
     * @param checkMalformed verify if it's a valid identifier
     * @return the resulting long
     */
    public static String readName(ByteBuffer buffer, boolean checkMalformed) {
        var length = (int) readVarUInt32(buffer);
        byte[] bytes = new byte[length];
        readBytes(buffer, bytes);
        var name = new String(bytes, StandardCharsets.UTF_8);
        if (checkMalformed && !isValidIdentifier(name)) {
            throw new MalformedException("malformed UTF-8 encoding");
        }
        return name;
    }

    private static boolean isValidIdentifier(String string) {
        return string.chars().allMatch(ch -> ch < 0x80 || Character.isUnicodeIdentifierPart(ch));
    }

    /**
     * Reads a LEB128 integer of {@code bits} width.
     * See <a href="https://webassembly.github.io/spec/core/binary/values.html#integers">5.2.2. Integers</a>
     * of the WebAssembly Core Specification.
     *
     * <p>The grammar bounds the byte count to {@code ceil(bits/7)} and, on the terminal byte,
     * bounds the payload to the unconsumed bit budget {@code r}: {@code uN} requires
     * {@code payload < 2^r}, {@code sN} requires the payload to be a sign extension over those
     * {@code r} bits. Overlong encodings within the byte budget remain valid.
     *
     * @param buffer the byte buffer
     * @param bits the width of the encoded integer, 32 or 64
     * @param signed whether to decode as {@code sN} rather than {@code uN}
     * @return the resulting long
     */
    private static long readLeb128(ByteBuffer buffer, int bits, boolean signed) {
        long result = 0;
        int shift = 0;
        while (true) {
            if (!buffer.hasRemaining()) {
                throw new MalformedException(
                        "integer too large, integer representation too long, length out of bounds");
            }
            byte b = buffer.get();
            int payload = b & 0x7F;
            int budget = bits - shift;

            if (budget <= 7) {
                // Terminal byte: no continuation allowed, and the payload must fit the budget.
                if ((b & 0x80) != 0) {
                    throw new MalformedException("integer representation too long");
                }
                int limit = 1 << (signed ? budget - 1 : budget);
                if (signed ? (payload >= limit && payload < 0x80 - limit) : payload >= limit) {
                    throw new MalformedException("integer too large");
                }
            }

            result |= (long) payload << shift;
            shift += 7;

            if ((b & 0x80) == 0) {
                if (signed && shift < 64 && (b & 0x40) != 0) {
                    result |= -1L << shift;
                }
                return result;
            }
        }
    }
}
