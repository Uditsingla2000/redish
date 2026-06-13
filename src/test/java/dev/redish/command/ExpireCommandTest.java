package dev.redish.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;

class ExpireCommandTest {

    private final Store store = new Store();
    private final ExpireCommand cmd = new ExpireCommand(store);

    @Test
    void expireSet() {
        store.set("k", "v".getBytes());
        assertEquals(1L, cmd.execute(List.of("EXPIRE", "k", "10")));
        assertTrue(store.ttl("k") > 0);
    }

    @Test
    void expireMissing() {
        assertEquals(0L, cmd.execute(List.of("EXPIRE", "nope", "10")));
    }

    @Test
    void expireExpired() throws InterruptedException {
        store.setex("tmp", "x".getBytes(), 50);
        Thread.sleep(100);
        assertEquals(0L, cmd.execute(List.of("EXPIRE", "tmp", "10")));
    }

    @Test
    void expireOverwritesExisting() {
        store.setex("k", "v".getBytes(), 5000);
        long orig = store.ttl("k");
        cmd.execute(List.of("EXPIRE", "k", "100"));
        assertTrue(store.ttl("k") > orig);
    }

    @Test
    void expireWrongArgs() {
        Object r = cmd.execute(List.of("EXPIRE", "k"));
        assertInstanceOf(ErrorResponse.class, r);
    }

    @Test
    void expireBadSeconds() {
        store.set("k", "v".getBytes());
        Object r = cmd.execute(List.of("EXPIRE", "k", "abc"));
        assertInstanceOf(ErrorResponse.class, r);
    }

    @Test
    void expireNegativeSeconds() {
        store.set("k", "v".getBytes());
        assertEquals(1L, cmd.execute(List.of("EXPIRE", "k", "-10")));
        assertNull(store.get("k")); // immediately expired
    }
}
