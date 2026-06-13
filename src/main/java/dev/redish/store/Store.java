package dev.redish.store;

import java.util.HashMap;
import java.util.Map;

public class Store {

    private record Value(Object data, long expiresAt) {
        boolean isExpired() {
            return expiresAt != -1 && System.currentTimeMillis() > expiresAt;
        }
    }

    private final Map<String, Value> map = new HashMap<>();

    public void set(String key, Object value) {
        map.put(key, new Value(value, -1));
    }

    public void setex(String key, Object value, long ttlMillis) {
        map.put(key, new Value(value, System.currentTimeMillis() + ttlMillis));
    }

    public Object get(String key) {
        Value v = map.get(key);
        if (v == null) return null;
        if (v.isExpired()) { map.remove(key); return null; }
        return v.data;
    }

    public long ttl(String key) {
        Value v = map.get(key);
        if (v == null) return -1;
        if (v.isExpired()) { map.remove(key); return -1; }
        if (v.expiresAt == -1) return -1;
        long remaining = v.expiresAt - System.currentTimeMillis();
        return remaining <= 0 ? -1 : (remaining + 999) / 1000;
    }

    public long del(String key) {
        Value v = map.remove(key);
        return v != null && !v.isExpired() ? 1 : 0;
    }

    public boolean expire(String key, long ttlMillis) {
        Value v = map.get(key);
        if (v == null || v.isExpired()) return false;
        map.put(key, new Value(v.data, System.currentTimeMillis() + ttlMillis));
        return true;
    }
}
