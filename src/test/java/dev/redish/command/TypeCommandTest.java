package dev.redish.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;

class TypeCommandTest {

    private Store store;
    private TypeCommand cmd;

    @BeforeEach
    void setUp() {
        store = new Store();
        cmd = new TypeCommand(store);
    }

    @Test
    void typeMissing() {
        assertEquals("none", cmd.execute(List.of("TYPE", "nope")));
    }

    @Test
    void typeString() {
        store.set("k", "hello".getBytes());
        assertEquals("string", cmd.execute(List.of("TYPE", "k")));
    }

    @Test
    void wrongArgCount() {
        Object result = cmd.execute(List.of("TYPE"));
        assertInstanceOf(ErrorResponse.class, result);
    }
}
