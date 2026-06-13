package dev.redish.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;

class TtlCommandTest {

    private final Store store = new Store();
    private final TtlCommand cmd = new TtlCommand(store);

    @Test
    void ttlNoExpiry() {
        store.set("perm", "x".getBytes());
        assertEquals(-1L, cmd.execute(List.of("TTL", "perm")));
    }

    @Test
    void ttlMissing() {
        assertEquals(-1L, cmd.execute(List.of("TTL", "nope")));
    }

    @Test
    void ttlWithExpiry() {
        store.setex("tmp", "y".getBytes(), 5000);
        Object r = cmd.execute(List.of("TTL", "tmp"));
        assertInstanceOf(Long.class, r);
        assertTrue((Long) r >= 4); // 5000ms → ~5 seconds
    }

    @Test
    void ttlExpired() throws InterruptedException {
        store.setex("tmp", "z".getBytes(), 50);
        Thread.sleep(100);
        assertEquals(-1L, cmd.execute(List.of("TTL", "tmp")));
    }

    @Test
    void ttlWrongArgs() {
        Object r = cmd.execute(List.of("TTL", "a", "b"));
        assertInstanceOf(ErrorResponse.class, r);
    }

    @Test
    void ttlTooFew() {
        Object r = cmd.execute(List.of("TTL"));
        assertInstanceOf(ErrorResponse.class, r);
    }
}
