package dev.redish.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class RespByteBufferTest {

    private static ByteBuffer toBuf(String resp) {
        return ByteBuffer.wrap(resp.getBytes(StandardCharsets.UTF_8));
    }

    private static String toStr(ByteBuffer buf) {
        buf.flip();
        byte[] data = new byte[buf.remaining()];
        buf.get(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    // ─── Parser: parse(ByteBuffer) ───────────────────────────────────

    @Test
    void parseSimpleString() {
        assertEquals("OK", RespParser.parse(toBuf("+OK\r\n")));
    }

    @Test
    void parseError() {
        assertEquals("ERR", RespParser.parse(toBuf("-ERR\r\n")));
    }

    @Test
    void parseInteger() {
        assertEquals(42L, RespParser.parse(toBuf(":42\r\n")));
    }

    @Test
    void parseIntegerNegative() {
        assertEquals(-1L, RespParser.parse(toBuf(":-1\r\n")));
    }

    @Test
    void parseBulkString() {
        assertEquals("hello", RespParser.parse(toBuf("$5\r\nhello\r\n")));
    }

    @Test
    void parseBulkStringEmpty() {
        assertEquals("", RespParser.parse(toBuf("$0\r\n\r\n")));
    }

    @Test
    void parseBulkStringNull() {
        assertNull(RespParser.parse(toBuf("$-1\r\n")));
    }

    @Test
    void parseEmptyArray() {
        assertEquals(List.of(), RespParser.parse(toBuf("*0\r\n")));
    }

    @Test
    void parseNullArray() {
        assertNull(RespParser.parse(toBuf("*-1\r\n")));
    }

    @Test
    void parseArrayOfMixedTypes() {
        Object result = RespParser.parse(toBuf("*2\r\n+OK\r\n:42\r\n"));
        assertEquals(List.of("OK", 42L), result);
    }

    @Test
    void parseNestedArray() {
        Object result = RespParser.parse(toBuf("*1\r\n*2\r\n+A\r\n+B\r\n"));
        List<?> inner = (List<?>) ((List<?>) result).get(0);
        assertEquals(List.of("A", "B"), inner);
    }

    // ─── Parser: partial / null on incomplete data ───────────────────

    @Test
    void parseEmptyBufferReturnsNull() {
        assertNull(RespParser.parse(ByteBuffer.allocate(0)));
    }

    @Test
    void parsePartialSimpleStringReturnsNull() {
        assertNull(RespParser.parse(toBuf("+OK\r")));
    }

    @Test
    void parsePartialBulkStringReturnsNull() {
        assertNull(RespParser.parse(toBuf("$5\r\nhel")));
    }

    @Test
    void parsePartialArrayReturnsNull() {
        assertNull(RespParser.parse(toBuf("*2\r\n+OK\r\n")));
    }

    @Test
    void parseCompleteAfterPartialRetry() {
        // Simulate two reads: first half, then full
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf.put("$5\r\nhel".getBytes(StandardCharsets.UTF_8));
        buf.flip();
        assertNull(RespParser.parse(buf)); // partial

        // Append more data
        buf.compact();
        buf.put("lo\r\n".getBytes(StandardCharsets.UTF_8));
        buf.flip();
        assertEquals("hello", RespParser.parse(buf)); // complete
    }

    @Test
    void parseArrayCompleteAfterPartialRetry() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf.put("*3\r\n+OK\r\n:42\r\n".getBytes(StandardCharsets.UTF_8));
        buf.flip();
        assertNull(RespParser.parse(buf)); // only 2 of 3 elements

        buf.compact();
        buf.put("+YES\r\n".getBytes(StandardCharsets.UTF_8));
        buf.flip();
        assertEquals(List.of("OK", 42L, "YES"), RespParser.parse(buf));
    }

    @Test
    void parseMultipleCommandsPipelined() {
        ByteBuffer buf = toBuf("*1\r\n$4\r\nPING\r\n*2\r\n$4\r\nPING\r\n$5\r\nhello\r\n");
        assertEquals(List.of("PING"), RespParser.parse(buf));
        buf.compact();
        buf.flip();
        assertEquals(List.of("PING", "hello"), RespParser.parse(buf));
    }

    // ─── Serializer: serialize(Object, ByteBuffer) ───────────────────

    @Test
    void serializeSimpleString() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf = RespSerializer.serialize("OK", buf);
        assertEquals("+OK\r\n", toStr(buf));
    }

    @Test
    void serializeBulkString() {
        String longStr = "hello\r\nworld";
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf = RespSerializer.serialize(longStr, buf);
        assertEquals("$12\r\nhello\r\nworld\r\n", toStr(buf));
    }

    @Test
    void serializeInteger() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf = RespSerializer.serialize(42L, buf);
        assertEquals(":42\r\n", toStr(buf));
    }

    @Test
    void serializeError() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf = RespSerializer.serialize(new ErrorResponse("ERR bad"), buf);
        assertEquals("-ERR bad\r\n", toStr(buf));
    }

    @Test
    void serializeNull() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf = RespSerializer.serialize(null, buf);
        assertEquals("$-1\r\n", toStr(buf));
    }

    @Test
    void serializeArray() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf = RespSerializer.serialize(List.of("PONG", 42L), buf);
        assertEquals("*2\r\n+PONG\r\n:42\r\n", toStr(buf));
    }

    @Test
    void serializeBulkStringForCRLF() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf = RespSerializer.serialize("a\r\nb", buf);
        assertEquals("$4\r\na\r\nb\r\n", toStr(buf));
    }

    // ─── Serializer: byte[] ──────────────────────────────────────────

    @Test
    void serializeByteArray() {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf = RespSerializer.serialize("hello".getBytes(StandardCharsets.UTF_8), buf);
        assertEquals("$5\r\nhello\r\n", toStr(buf));
    }

    // ─── Serializer: grow() ──────────────────────────────────────────

    @Test
    void growOnLargeString() {
        String big = "x\n" + "y".repeat(10000); // contains newline → forces bulk
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf = RespSerializer.serialize(big, buf);
        String result = toStr(buf);
        int dataLen = big.getBytes(StandardCharsets.UTF_8).length;
        int prefixLen = Integer.toString(dataLen).length() + 1;
        assertTrue(result.startsWith("$" + dataLen + "\r\n"));
        assertEquals(dataLen + prefixLen + 4, result.length());
    }

    @Test
    void growOnLargeBulkString() {
        String big = "a\nb" + "x".repeat(10000); // contains newline → force bulk
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf = RespSerializer.serialize(big, buf);
        String result = toStr(buf);
        assertTrue(result.startsWith("$"));
        assertTrue(result.endsWith("\r\n"));
    }

    @Test
    void growOnArrayWithManyElements() {
        var list = List.of("x".repeat(500), "y".repeat(500), "z".repeat(500));
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf = RespSerializer.serialize(list, buf);
        String result = toStr(buf);
        assertTrue(result.startsWith("*3\r\n"));
        assertEquals(3, result.split("\r\n").length - 1);
    }

    // ─── Parser: unknown type byte throws ────────────────────────────

    @Test
    void parseUnknownTypeAsInlineCommand() {
        assertEquals(List.of("~bad"), RespParser.parse(toBuf("~bad\r\n")));
    }

    // ─── Parser: bulk string edge cases ──────────────────────────────

    @Test
    void parseBulkStringWithNewlines() {
        assertEquals("hello\r\nworld", RespParser.parse(toBuf("$12\r\nhello\r\nworld\r\n")));
    }

    @Test
    void parseBulkStringLarge() {
        String big = "a".repeat(10000);
        assertEquals(big, RespParser.parse(toBuf("$10000\r\n" + big + "\r\n")));
    }
}
