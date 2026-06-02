package dev.redish.resp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class RespParserTest {

    private static InputStream toStream(String resp) {
        return new ByteArrayInputStream(resp.getBytes(StandardCharsets.UTF_8));
    }

    // ── Simple string ────────────────────────────────────────────────

    @Test
    void parseSimpleString() throws IOException {
        Object result = RespParser.parse(toStream("+OK\r\n"));
        assertEquals("OK", result);
    }

    // ── Error ────────────────────────────────────────────────────────

    @Test
    void parseError() throws IOException {
        Object result = RespParser.parse(toStream("-ERR\r\n"));
        assertEquals("ERR", result);
    }

    // ── Integer ──────────────────────────────────────────────────────

    @Test
    void parseInteger() throws IOException {
        Object result = RespParser.parse(toStream(":42\r\n"));
        assertEquals(42L, result);
    }

    @Test
    void parseIntegerNegative() throws IOException {
        Object result = RespParser.parse(toStream(":-1\r\n"));
        assertEquals(-1L, result);
    }

    // ── Bulk string (individual cases) ───────────────────────────────

    @Test
    void parseBulkStringNull() throws IOException {
        Object result = RespParser.parse(toStream("$-1\r\n"));
        assertNull(result);
    }

    static Stream<BulkStringCase> bulkStringCases() {
        return Stream.of(
            new BulkStringCase(5, "hello"),
            new BulkStringCase(0, ""),
            new BulkStringCase(1, "a"),
            new BulkStringCase(11, "hello world")
        );
    }

    record BulkStringCase(int length, String expected) {}

    @ParameterizedTest(name = "parseBulkString({0}) → \"{1}\"")
    @MethodSource("bulkStringCases")
    void parseBulkString(BulkStringCase c) throws IOException {
        String resp = "$" + c.length + "\r\n" + c.expected + "\r\n";
        Object result = RespParser.parse(toStream(resp));
        assertEquals(c.expected, result);
    }

    // ── Array ────────────────────────────────────────────────────────

    @Test
    void parseEmptyArray() throws IOException {
        Object result = RespParser.parse(toStream("*0\r\n"));
        assertEquals(List.of(), result);
    }

    @Test
    void parseNullArray() throws IOException {
        Object result = RespParser.parse(toStream("*-1\r\n"));
        assertNull(result);
    }

    @Test
    void parseArrayOfMixedTypes() throws IOException {
        Object result = RespParser.parse(toStream("*2\r\n+OK\r\n:42\r\n"));
        assertEquals(List.of("OK", 42L), result);
    }

    @Test
    void parseNestedArray() throws IOException {
        Object result = RespParser.parse(toStream("*1\r\n*2\r\n+A\r\n+B\r\n"));
        List<?> inner = (List<?>) ((List<?>) result).get(0);
        assertEquals(List.of("A", "B"), inner);
    }
}
