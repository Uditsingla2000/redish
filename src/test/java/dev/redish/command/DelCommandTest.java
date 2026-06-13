package dev.redish.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;

class DelCommandTest {

    private final Store store = new Store();
    private final DelCommand cmd = new DelCommand(store);

    @Test
    void deleteExisting() {
        store.set("k", "v".getBytes());
        assertEquals(1L, cmd.execute(List.of("DEL", "k")));
        assertNull(store.get("k"));
    }

    @Test
    void deleteMissing() {
        assertEquals(0L, cmd.execute(List.of("DEL", "nope")));
    }

    @Test
    void deleteMultiple() {
        store.set("a", "1".getBytes());
        store.set("b", "2".getBytes());
        store.set("c", "3".getBytes());
        assertEquals(3L, cmd.execute(List.of("DEL", "a", "b", "c")));
    }

    @Test
    void deleteSomeMissing() {
        store.set("a", "1".getBytes());
        assertEquals(1L, cmd.execute(List.of("DEL", "a", "nope")));
    }

    @Test
    void deleteExpiredDoesNotCount() throws InterruptedException {
        store.setex("tmp", "x".getBytes(), 50);
        Thread.sleep(100);
        assertEquals(0L, cmd.execute(List.of("DEL", "tmp")));
    }

    @Test
    void deleteNoArgs() {
        Object r = cmd.execute(List.of("DEL"));
        assertInstanceOf(ErrorResponse.class, r);
    }
}
