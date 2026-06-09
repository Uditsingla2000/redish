package dev.redish.resp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Simple RESP (Redis Serialization Protocol) serializer.
 *
 * It provides static methods to write Java objects to an {@link OutputStream}
 * using the RESP wire format.
 *
 * Supported Java types → RESP mapping:
 *   - String                 → Simple String (+) if it does not contain CR/LF,
 *                             otherwise Bulk String ($).
 *   - Long / Integer         → Integer (:)
 *   - byte[]                 → Bulk String ($)
 *   - List&lt;Object&gt;           → Array (*). Elements are serialized recursively.
 *   - null                   → Null Bulk String ($-1) or Null Array (*-1) depending on context.
 */
public class RespSerializer {

    /** Serialize a generic Java object as RESP. */
    public static void serialize(Object obj, OutputStream out) throws IOException {
        if (obj == null) {
            writeNullBulkString(out);
            return;
        }
        if (obj instanceof ErrorResponse e) {
            writeError(e.message(), out);
            return;
        }
        if (obj instanceof String) {
            String s = (String) obj;
            // If the string contains CR or LF we must use Bulk String to preserve them.
            if (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
                writeBulkString(s, out);
            } else {
                writeSimpleString(s, out);
            }
        } else if (obj instanceof Number) {
            writeInteger(((Number) obj).longValue(), out);
        } else if (obj instanceof byte[]) {
            writeBulkString((byte[]) obj, out);
        } else if (obj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) obj;
            writeArray(list, out);
        } else {
            // Fallback – convert to string as a bulk string.
            writeBulkString(obj.toString(), out);
        }
    }

    private static void writeSimpleString(String s, OutputStream out) throws IOException {
        out.write('+');
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
    }

    private static void writeError(String msg, OutputStream out) throws IOException {
        out.write('-');
        out.write(msg.getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
    }

    private static void writeInteger(long value, OutputStream out) throws IOException {
        out.write(':');
        out.write(Long.toString(value).getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
    }

    private static void writeBulkString(String s, OutputStream out) throws IOException {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        out.write('$');
        out.write(Integer.toString(data.length).getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
        out.write(data);
        out.write('\r');
        out.write('\n');
    }

    private static void writeBulkString(byte[] data, OutputStream out) throws IOException {
        out.write('$');
        out.write(Integer.toString(data.length).getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
        out.write(data);
        out.write('\r');
        out.write('\n');
    }

    private static void writeNullBulkString(OutputStream out) throws IOException {
        out.write("$-1\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeArray(List<Object> list, OutputStream out) throws IOException {
        if (list == null) {
            out.write("*-1\r\n".getBytes(StandardCharsets.UTF_8));
            return;
        }
        out.write('*');
        out.write(Integer.toString(list.size()).getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
        for (Object elem : list) {
            serialize(elem, out);
        }
    }

    // ─── ByteBuffer-based serialization ───

    public static void serialize(Object obj, ByteBuffer buf) {
        if (obj instanceof ErrorResponse e)       { writeError(e.message(), buf); }
        else if (obj instanceof String s)          { writeString(s, buf); }
        else if (obj instanceof Long l)            { writeInteger(l, buf); }
        else if (obj instanceof List<?> list)      { writeArray(list, buf); }
        else if (obj == null)                      { writeNullBulkString(buf); }
        else                                       { writeBulkString(obj.toString(), buf); }
    }

    private static void writeString(String s, ByteBuffer buf) {
        if (s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0) {
            writeBulkString(s, buf);
        } else {
            writeSimpleString(s, buf);
        }
    }

    private static void writeSimpleString(String s, ByteBuffer buf) {
        buf.put((byte) '+');
        buf.put(s.getBytes(StandardCharsets.UTF_8));
        buf.put((byte) '\r');
        buf.put((byte) '\n');
    }

    private static void writeError(String msg, ByteBuffer buf) {
        buf.put((byte) '-');
        buf.put(msg.getBytes(StandardCharsets.UTF_8));
        buf.put((byte) '\r');
        buf.put((byte) '\n');
    }

    private static void writeInteger(long value, ByteBuffer buf) {
        buf.put((byte) ':');
        buf.put(Long.toString(value).getBytes(StandardCharsets.UTF_8));
        buf.put((byte) '\r');
        buf.put((byte) '\n');
    }

    private static void writeBulkString(String s, ByteBuffer buf) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        buf.put((byte) '$');
        buf.put(Integer.toString(data.length).getBytes(StandardCharsets.UTF_8));
        buf.put((byte) '\r');
        buf.put((byte) '\n');
        buf.put(data);
        buf.put((byte) '\r');
        buf.put((byte) '\n');
    }

    private static void writeNullBulkString(ByteBuffer buf) {
        buf.put((byte) '$');
        buf.put((byte) '-');
        buf.put((byte) '1');
        buf.put((byte) '\r');
        buf.put((byte) '\n');
    }

    @SuppressWarnings("unchecked")
    private static void writeArray(List<?> list, ByteBuffer buf) {
        if (list == null) {
            writeNullBulkString(buf);
            return;
        }
        buf.put((byte) '*');
        buf.put(Integer.toString(list.size()).getBytes(StandardCharsets.UTF_8));
        buf.put((byte) '\r');
        buf.put((byte) '\n');
        for (Object elem : list) {
            serialize(elem, buf);
        }
    }
}
