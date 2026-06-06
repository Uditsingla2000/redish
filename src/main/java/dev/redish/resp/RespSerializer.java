package dev.redish.resp;

import java.io.IOException;
import java.io.OutputStream;
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
}
