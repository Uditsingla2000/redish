package dev.redish.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;

class GetCommandTest {

    private final Store store = new Store();
    private final GetCommand cmd = new GetCommand(store);

    @Test
    void getHit() {
        store.set("k", "hello".getBytes());
        assertArrayEquals("hello".getBytes(), (byte[]) cmd.execute(List.of("GET", "k")));
    }

    @Test
    void getMiss() {
        assertNull(cmd.execute(List.of("GET", "nope")));
    }

    @Test
    void getWrongArgs() {
        Object r = cmd.execute(List.of("GET"));
        assertInstanceOf(ErrorResponse.class, r);
    }

    @Test
    void getTooManyArgs() {
        Object r = cmd.execute(List.of("GET", "k", "x"));
        assertInstanceOf(ErrorResponse.class, r);
    }

    @Test
    void getExpired() throws InterruptedException {
        store.setex("tmp", "data".getBytes(), 50);
        Thread.sleep(100);
        assertNull(cmd.execute(List.of("GET", "tmp")));
    }
}
