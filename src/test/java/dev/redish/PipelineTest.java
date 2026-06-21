package dev.redish;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.redish.command.CommandRegistry;
import dev.redish.resp.ErrorResponse;
import dev.redish.resp.RespException;
import dev.redish.resp.RespParser;
import dev.redish.resp.RespSerializer;
import dev.redish.store.Store;

class PipelineTest {

    private final Store store = new Store();
    private final CommandRegistry registry = new CommandRegistry(store);

    /** Simulates the handleRead drain loop. Returns serialized response bytes. */
    private String drainLoop(ByteBuffer buf) {
        StringBuilder sb = new StringBuilder();
        int processed = 0;
        while (processed++ < 5000) {
            int start = buf.position();
            Object parsed;
            try {
                parsed = RespParser.parse(buf);
            } catch (RespException e) {
                sb.append("-ERR ").append(e.getMessage()).append("\r\n");
                break;
            }
            if (parsed == null) {
                buf.position(start);
                break;
            }
            if (!(parsed instanceof List<?> args) || args.isEmpty()) {
                sb.append("-ERR expected array\r\n");
                break;
            }
            String cmdName = ((String) args.get(0)).toUpperCase();
            Object result = registry.get(cmdName).execute((List<Object>) args);
            sb.append(serializeToString(result));
        }
        return sb.toString();
    }

    private static String serializeToString(Object obj) {
        ByteBuffer buf = ByteBuffer.allocate(64);
        buf = RespSerializer.serialize(obj, buf);
        buf.flip();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    void twoCommands() {
        store.set("k", "v".getBytes());
        byte[] wire = ("*2\r\n$3\r\nGET\r\n$1\r\nk\r\n" +
                       "*2\r\n$3\r\nGET\r\n$1\r\nk\r\n").getBytes(StandardCharsets.UTF_8);
        String result = drainLoop(ByteBuffer.wrap(wire));
        assertEquals("$1\r\nv\r\n$1\r\nv\r\n", result);
    }

    @Test
    void setThenGet() {
        byte[] wire = ("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nvalue\r\n" +
                       "*2\r\n$3\r\nGET\r\n$1\r\nk\r\n").getBytes(StandardCharsets.UTF_8);
        String result = drainLoop(ByteBuffer.wrap(wire));
        assertEquals("+OK\r\n$5\r\nvalue\r\n", result);
    }

    @Test
    void tenCommands() {
        StringBuilder wire = new StringBuilder();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            wire.append("*3\r\n$3\r\nSET\r\n$4\r\nkey")
                .append(i).append("\r\n$5\r\nvalue\r\n");
            expected.append("+OK\r\n");
        }
        String result = drainLoop(ByteBuffer.wrap(wire.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected.toString(), result);
        // Verify all keys were stored
        for (int i = 0; i < 10; i++) {
            assertNotNull(store.get("key" + i));
        }
    }

    @Test
    void partialFrame() {
        byte[] wire = ("*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\nb\r\n" +
                       "*2\r\n$3\r\nGE").getBytes(StandardCharsets.UTF_8); // incomplete GET
        String result = drainLoop(ByteBuffer.wrap(wire));
        assertEquals("+OK\r\n", result);
        assertArrayEquals("b".getBytes(), (byte[]) store.get("a"));
    }

    @Test
    void commandErrorContinues() {
        byte[] wire = ("*2\r\n$3\r\nGET\r\n$1\r\nx\r\n" +
                       "*1\r\n$3\r\nGET\r\n" +              // GET with no key → error
                       "*2\r\n$3\r\nGET\r\n$1\r\ny\r\n").getBytes(StandardCharsets.UTF_8);
        store.set("x", "1".getBytes());
        store.set("y", "2".getBytes());
        String result = drainLoop(ByteBuffer.wrap(wire));
        assertTrue(result.contains("$1\r\n1\r\n"));
        assertTrue(result.contains("-ERR wrong number of arguments for 'GET' command"));
        assertTrue(result.contains("$1\r\n2\r\n"));
    }

    @Test
    void protocolErrorDisconnects() {
        byte[] wire = ("*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\nb\r\n" +
                       "$XX\r\n").getBytes(StandardCharsets.UTF_8); // bad bulk string length
        String result = drainLoop(ByteBuffer.wrap(wire));
        assertTrue(result.contains("+OK\r\n"));
        assertTrue(result.contains("-ERR Invalid bulk string length: XX"));
    }

    @Test
    void unknownCommandReturnsError() {
        byte[] wire = "*2\r\n$7\r\nUNKNOWN\r\n$1\r\nx\r\n".getBytes(StandardCharsets.UTF_8);
        String result = drainLoop(ByteBuffer.wrap(wire));
        assertTrue(result.startsWith("-ERR unknown command 'UNKNOWN'"));
    }

    @Test
    void wrongCommandArgsReturnsErrorAndContinues() {
        store.set("k", "v".getBytes());
        byte[] wire = ("*1\r\n$3\r\nGET\r\n" +              // GET with no key → error
                       "*2\r\n$3\r\nGET\r\n$1\r\nk\r\n").getBytes(StandardCharsets.UTF_8);
        String result = drainLoop(ByteBuffer.wrap(wire));
        assertTrue(result.contains("-ERR wrong number of arguments for 'GET' command"));
        assertTrue(result.contains("$1\r\nv\r\n"));
    }

    @Test
    void emptyPipeline() {
        byte[] wire = "\r\n".getBytes(StandardCharsets.UTF_8);
        String result = drainLoop(ByteBuffer.wrap(wire));
        assertTrue(result.contains("-ERR expected array"));
    }

    @Test
    void mixedRespAndInline() {
        byte[] wire = ("*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\nb\r\n" +
                       "GET a\r\n").getBytes(StandardCharsets.UTF_8);
        String result = drainLoop(ByteBuffer.wrap(wire));
        assertTrue(result.contains("+OK"));
        assertTrue(result.contains("$1\r\nb\r\n"));
    }

    @Test
    void capLimit() {
        StringBuilder wire = new StringBuilder();
        for (int i = 0; i < 5001; i++) {
            String key = "k" + i;
            wire.append("*3\r\n$3\r\nSET\r\n$")
                .append(key.length()).append("\r\n").append(key).append("\r\n")
                .append("$1\r\nx\r\n");
        }
        String result = drainLoop(ByteBuffer.wrap(wire.toString().getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < 5000; i++) {
            assertNotNull(store.get("k" + i), "k" + i + " should exist");
        }
        assertNull(store.get("k5000"), "k5000 should not exist (cap hit)");
    }
}
