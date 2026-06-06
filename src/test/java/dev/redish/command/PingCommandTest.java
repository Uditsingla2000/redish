package dev.redish.command;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.redish.resp.ErrorResponse;
import org.junit.jupiter.api.Test;

class PingCommandTest {

    private final PingCommand cmd = new PingCommand();

    @Test
    void pingNoArgs() {
        Object result = cmd.execute(List.of("PING"));
        assertEquals("PONG", result);
    }

    @Test
    void pingWithArg() {
        Object result = cmd.execute(List.of("PING", "hello"));
        assertInstanceOf(byte[].class, result);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), (byte[]) result);
    }

    @Test
    void pingWithArgEmpty() {
        Object result = cmd.execute(List.of("PING", ""));
        assertInstanceOf(byte[].class, result);
        assertArrayEquals("".getBytes(StandardCharsets.UTF_8), (byte[]) result);
    }

    @Test
    void pingTooManyArgs() {
        Object result = cmd.execute(List.of("PING", "a", "b"));
        assertInstanceOf(ErrorResponse.class, result);
        assertEquals("ERR wrong number of arguments for 'PING' command",
                ((ErrorResponse) result).message());
    }
}
