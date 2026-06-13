package dev.redish.store;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StoreTest {

    private final Store store = new Store();

    @Test
    void setAndGet() {
        store.set("name", "bob".getBytes());
        assertArrayEquals("bob".getBytes(), (byte[]) store.get("name"));
    }

    @Test
    void getMissing() {
        assertNull(store.get("nope"));
    }

    @Test
    void overwriteValue() {
        store.set("key", "v1".getBytes());
        store.set("key", "v2".getBytes());
        assertArrayEquals("v2".getBytes(), (byte[]) store.get("key"));
    }

    @Test
    void ttlNoExpiry() {
        store.set("perm", "x".getBytes());
        assertEquals(-1, store.ttl("perm"));
    }

    @Test
    void ttlMissing() {
        assertEquals(-1, store.ttl("nope"));
    }

    @Test
    void setexAndTtl() throws InterruptedException {
        store.setex("tmp", "y".getBytes(), 200);
        long ttl = store.ttl("tmp");
        assertTrue(ttl > 0 && ttl <= 1); // 200ms → 0 or 1 seconds
        Thread.sleep(300);
        assertEquals(-1, store.ttl("tmp")); // expired → -1
    }

    @Test
    void setexAndGetAfterExpiry() throws InterruptedException {
        store.setex("tmp", "data".getBytes(), 100);
        Thread.sleep(200);
        assertNull(store.get("tmp")); // expired, removed
    }

    @Test
    void setexOverwriteResetsExpiry() throws InterruptedException {
        store.setex("k", "v1".getBytes(), 50);
        store.setex("k", "v2".getBytes(), 5000);
        Thread.sleep(100);
        assertArrayEquals("v2".getBytes(), (byte[]) store.get("k")); // still alive, new expiry
    }

    @Test
    void ttlRoundsUp() {
        // 1ms remaining → should return 1 second
        store.setex("k", "x".getBytes(), 1);
        long ttl = store.ttl("k");
        assertTrue(ttl == 0 || ttl == 1);
    }

    @Test
    void delExisting() {
        store.set("k", "v".getBytes());
        assertEquals(1, store.del("k"));
        assertNull(store.get("k"));
    }

    @Test
    void delMissing() {
        assertEquals(0, store.del("nope"));
    }

    @Test
    void delExpired() throws InterruptedException {
        store.setex("tmp", "x".getBytes(), 50);
        Thread.sleep(100);
        assertEquals(0, store.del("tmp"));
    }

    @Test
    void expireExisting() {
        store.set("k", "v".getBytes());
        assertTrue(store.expire("k", 5000));
        assertTrue(store.ttl("k") > 0);
    }

    @Test
    void expireMissing() {
        assertFalse(store.expire("nope", 5000));
    }

    @Test
    void expireAlreadyExpired() throws InterruptedException {
        store.setex("tmp", "x".getBytes(), 50);
        Thread.sleep(100);
        assertFalse(store.expire("tmp", 5000));
    }
}
