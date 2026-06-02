package dev.redish.resp;

import java.io.IOException;
import java.io.InputStream;
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
}
