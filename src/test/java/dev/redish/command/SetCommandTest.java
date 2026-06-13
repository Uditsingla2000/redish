package dev.redish.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;

class SetCommandTest {

    private final Store store = new Store();
    private final SetCommand cmd = new SetCommand(store);
    private final List<Object> set = List.of("SET", "k", "v");

    @Test
    void setOk() {
        assertEquals("OK", cmd.execute(set));
        assertArrayEquals("v".getBytes(), (byte[]) store.get("k"));
    }

    @Test
    void setEx() {
        assertEquals("OK", cmd.execute(List.of("SET", "k", "v", "EX", "10")));
        assertNotNull(store.get("k"));
    }

    @Test
    void setMissingKey() {
        Object r = cmd.execute(List.of("SET"));
        assertInstanceOf(ErrorResponse.class, r);
    }

    @Test
    void setExBadSyntax() {
        Object r = cmd.execute(List.of("SET", "k", "v", "XX", "10"));
        assertInstanceOf(ErrorResponse.class, r);
    }

    @Test
    void setExZero() {
        Object r = cmd.execute(List.of("SET", "k", "v", "EX", "0"));
        assertInstanceOf(ErrorResponse.class, r);
    }

    @Test
    void setExNotInteger() {
        Object r = cmd.execute(List.of("SET", "k", "v", "EX", "abc"));
        assertInstanceOf(ErrorResponse.class, r);
    }
}
