package dev.redish.resp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple RESP (Redis Serialization Protocol) parser.
 *
 * It reads from a raw {@link InputStream} and returns Java objects representing the RESP data.
 * The mapping is:
 *   - SIMPLE_STRING (+) -> {@link String}
 *   - ERROR         (-) -> {@link String} (error message)
 *   - INTEGER       (:) -> {@link Long}
 *   - BULK_STRING   ($) -> {@link String} (null if length is -1)
 *   - ARRAY         (*) -> {@link List<Object>} (null if length is -1)
 *
 * This implementation is deliberately minimal and does not attempt full protocol compliance such as
 * handling CRLF fragments across reads, timeouts, or binary-safe bulk strings beyond converting bytes to UTF‑8.
 */
public class RespParser {

    /** Reads a complete RESP value from the given stream. */
    public static Object parse(InputStream in) throws IOException {
        int prefix = in.read();
        if (prefix == -1) {
            throw new IOException("End of stream while expecting RESP type identifier");
        }
        RespType type = RespType.fromByte((byte) prefix);
        switch (type) {
            case SIMPLE_STRING:
                return readLine(in);
            case ERROR:
                return readLine(in); // Errors are treated as plain strings
            case INTEGER:
                return Long.parseLong(readLine(in));
            case BULK_STRING:
                return readBulkString(in);
            case ARRAY:
                return readArray(in);
            default:
                throw new IllegalArgumentException("Unsupported RESP type: " + type);
        }
    }

    /** Reads a line terminated by CRLF ("\r\n") and returns the content without the terminator. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        while (true) {
            int curr = in.read();
            if (curr == -1) {
                throw new IOException("Unexpected end of stream while reading line");
            }
            if (prev == '\r' && curr == '\n') {
                sb.setLength(sb.length() - 1); // discard the preceding '\r'
                return sb.toString();
            }
            sb.append((char) curr);
            prev = curr;
        }
    }

    /** Reads a bulk string. Returns {@code null} if the length prefix is -1. */
    private static String readBulkString(InputStream in) throws IOException {
        String lenStr = readLine(in);
        int length = Integer.parseInt(lenStr);
        if (length == -1) {
            return null; // Null bulk string
        }
        if (length < -1) {
            throw new IOException("Invalid bulk string length: " + length);
        }
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int r = in.read(data, read, length - read);
            if (r == -1) {
                throw new IOException("Unexpected end of stream while reading bulk string");
            }
            read += r;
        }
        // Consume trailing CRLF
        if (in.read() != '\r' || in.read() != '\n') {
            throw new IOException("Bulk string not terminated with CRLF");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /** Reads an array of RESP values. Returns {@code null} if the length prefix is -1. */
    private static List<Object> readArray(InputStream in) throws IOException {
        String lenStr = readLine(in);
        int count = Integer.parseInt(lenStr);
        if (count == -1) {
            return null; // Null array
        }
        List<Object> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(parse(in));
        }
        return list;
    }

    // ─── ByteBuffer-based parseLine (peek with buf.get(i), no advance) ───

    private static String parseLine(ByteBuffer buf) {
        int start = buf.position();
        for (int i = start; i < buf.limit(); i++) {
            if (buf.get(i) == '\r' && i + 1 < buf.limit() && buf.get(i + 1) == '\n') {
                byte[] line = new byte[i - start];
                buf.get(line);
                buf.get(); // \r
                buf.get(); // \n
                return new String(line, StandardCharsets.UTF_8);
            }
        }
        buf.position(start);
        return null;
    }

    // ─── ByteBuffer-based parse entrypoint ───

    /** RESP type → parser method, null if incomplete. */
    public static Object parse(ByteBuffer buf) {
        if (buf.remaining() == 0) return null;
        byte prefix = buf.get(buf.position());
        if (isResTypeByte(prefix)) {
            RespType type = RespType.fromByte(prefix);
            return switch (type) {
                case SIMPLE_STRING -> parseSimpleString(buf);
                case ERROR        -> parseError(buf);
                case INTEGER      -> parseInteger(buf);
                case BULK_STRING  -> parseBulkString(buf);
                case ARRAY        -> parseArray(buf);
            };
        }
        // Inline command — not a RESP type marker, read as space-separated tokens
        return parseInline(buf);
    }

    /** "+" → consume marker, read CRLF line. */
    private static String parseSimpleString(ByteBuffer buf) {
        buf.get(); // consume '+'
        return parseLine(buf);
    }

    /** "-" → consume marker, read CRLF line. */
    private static String parseError(ByteBuffer buf) {
        buf.get(); // consume '-'
        return parseLine(buf);
    }

    /** ":" → consume marker, parse CRLF line as long. */
    private static Long parseInteger(ByteBuffer buf) {
        buf.get(); // consume ':'
        String line = parseLine(buf);
        if (line == null) return null;
        return Long.parseLong(line);
    }

    /** "$" → length, then N bytes + CRLF. null on $-1. */
    private static String parseBulkString(ByteBuffer buf) {
        int start = buf.position();
        buf.get(); // consume '$'
        String lenStr = parseLine(buf);
        if (lenStr == null) { buf.position(start); return null; }
        int len = Integer.parseInt(lenStr);
        if (len == -1) return null;
        if (buf.remaining() < len + 2) { buf.position(start); return null; }
        byte[] data = new byte[len];
        buf.get(data);
        if (buf.get() != '\r' || buf.get() != '\n') {
            throw new RespException("Bulk string not terminated with CRLF");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    /** "*" → count, then N nested values. null on *-1. */
    private static List<Object> parseArray(ByteBuffer buf) {
        int start = buf.position();
        buf.get(); // consume '*'
        String lenStr = parseLine(buf);
        if (lenStr == null) { buf.position(start); return null; }
        int len = Integer.parseInt(lenStr);
        if (len == -1) return null;
        List<Object> list = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            Object element = parse(buf);
            if (element == null) { buf.position(start); return null; }
            list.add(element);
        }
        return list;
    }

    /** Returns true if the byte is a known RESP type prefix. */
    private static boolean isResTypeByte(byte b) {
        return b == '+' || b == '-' || b == ':' || b == '$' || b == '*';
    }

    /**
     * Parse an inline command: read until CRLF (or LF), split on whitespace,
     * return as a list of bulk-string tokens (same shape as a RESP array).
     */
    private static List<Object> parseInline(ByteBuffer buf) {
        int start = buf.position();
        // Scan for \r\n or \n
        int lineEnd = -1;
        int limit = buf.limit();
        for (int i = start; i < limit; i++) {
            byte b = buf.get(i);
            if (b == '\r' && i + 1 < limit && buf.get(i + 1) == '\n') {
                lineEnd = i;
                break;
            }
            if (b == '\n') {
                lineEnd = i;
                break;
            }
        }
        if (lineEnd == -1) {
            buf.position(start);
            return null;
        }

        // Extract the line content (without CR/LF)
        int lineLen = lineEnd - start;
        String line;
        if (buf.get(lineEnd) == '\r') {
            byte[] data = new byte[lineLen];
            buf.get(data);
            buf.get(); // \r
            buf.get(); // \n
            line = new String(data, StandardCharsets.UTF_8);
        } else {
            byte[] data = new byte[lineLen];
            buf.get(data);
            buf.get(); // \n
            line = new String(data, StandardCharsets.UTF_8);
        }

        // Split on whitespace
        String[] tokens = line.strip().split("\\s+");
        if (tokens.length == 1 && tokens[0].isEmpty()) {
            return List.of();
        }
        return List.of((Object[]) tokens);
    }
}
