package dev.redish.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;

class IncrCommandTest {

    private Store store;
    private IncrCommand cmd;

    @BeforeEach
    void setUp() {
        store = new Store();
        cmd = new IncrCommand(store);
    }

    @Test
    void incrMissingKey() {
        assertEquals(1L, cmd.execute(List.of("INCR", "counter")));
    }

    @Test
    void incrExisting() {
        store.set("counter", "5".getBytes());
        assertEquals(6L, cmd.execute(List.of("INCR", "counter")));
    }

    @Test
    void incrTwice() {
        cmd.execute(List.of("INCR", "x"));
        cmd.execute(List.of("INCR", "x"));
        assertEquals(3L, cmd.execute(List.of("INCR", "x")));
    }

    @Test
    void incrNonNumeric() {
        store.set("k", "abc".getBytes());
        Object result = cmd.execute(List.of("INCR", "k"));
        assertInstanceOf(ErrorResponse.class, result);
    }

    @Test
    void wrongArgCount() {
        Object result = cmd.execute(List.of("INCR"));
        assertInstanceOf(ErrorResponse.class, result);
    }

    @Test
    void isWrite() {
        assertTrue(cmd.isWrite());
    }
}
