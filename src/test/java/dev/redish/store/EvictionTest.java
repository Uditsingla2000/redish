package dev.redish.store;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class EvictionTest {

    @Test
    void underMaxKeys() {
        Store store = new Store();
        store.setMaxKeys(5);
        store.setEvictionPolicy(EvictionPolicy.ALLKEYS_RANDOM);
        for (int i = 0; i < 4; i++) {
            store.set("k" + i, ("v" + i).getBytes());
        }
        assertEquals(4, store.size());
        assertNull(store.evictIfNeeded());
    }

    @Test
    void noevictionBlocksWrite() {
        Store store = new Store();
        store.setMaxKeys(3);
        store.setEvictionPolicy(EvictionPolicy.NOEVICTION);
        store.set("a", "1".getBytes());
        store.set("b", "2".getBytes());
        store.set("c", "3".getBytes());
        String err = store.evictIfNeeded();
        assertNotNull(err);
        assertTrue(err.contains("maxkeys"));
    }

    @Test
    void allkeysRandomEvictsSomething() {
        Store store = new Store();
        store.setMaxKeys(3);
        store.setEvictionPolicy(EvictionPolicy.ALLKEYS_RANDOM);
        store.set("a", "1".getBytes());
        store.set("b", "2".getBytes());
        store.set("c", "3".getBytes());
        assertEquals(3, store.size());
        assertNull(store.evictIfNeeded());
        assertEquals(2, store.size());
    }

    @Test
    void maxKeysZeroMeansNoEviction() {
        Store store = new Store();
        store.setEvictionPolicy(EvictionPolicy.ALLKEYS_RANDOM);
        for (int i = 0; i < 100; i++) {
            store.set("k" + i, ("v" + i).getBytes());
        }
        assertNull(store.evictIfNeeded());
        assertEquals(100, store.size());
    }

    @Test
    void evictIfNeededRemovesOnlyOneKey() {
        Store store = new Store();
        store.setMaxKeys(2);
        store.setEvictionPolicy(EvictionPolicy.ALLKEYS_RANDOM);
        store.set("a", "1".getBytes());
        store.set("b", "2".getBytes());
        store.evictIfNeeded();
        assertEquals(1, store.size());
    }

    @Test
    void noevictionReadCommandsWork() {
        Store store = new Store();
        store.setMaxKeys(1);
        store.setEvictionPolicy(EvictionPolicy.NOEVICTION);
        store.set("k", "v".getBytes());
        assertNotNull(store.get("k"));
        assertEquals("string", store.getType("k").respName());
    }

    @Test
    void fromString() {
        assertEquals(EvictionPolicy.NOEVICTION, EvictionPolicy.fromString("noeviction"));
        assertEquals(EvictionPolicy.ALLKEYS_RANDOM, EvictionPolicy.fromString("allkeys-random"));
        assertEquals(EvictionPolicy.NOEVICTION, EvictionPolicy.fromString("unknown"));
    }

    @Test
    void configName() {
        assertEquals("noeviction", EvictionPolicy.NOEVICTION.configName());
        assertEquals("allkeys-random", EvictionPolicy.ALLKEYS_RANDOM.configName());
    }
}
