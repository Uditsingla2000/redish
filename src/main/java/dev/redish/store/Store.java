package dev.redish.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Store {

    public record Entry(String key, byte[] data, long expiresAt) {
        static Entry fromMapEntry(String key, RedisObject obj, long expiresAt) {
            return new Entry(key, obj.stringBytes(), expiresAt);
        }
    }

    private record Value(RedisObject data, long expiresAt) {
        boolean isExpired() {
            return expiresAt != -1 && System.currentTimeMillis() > expiresAt;
        }
    }

    private final Map<String, Value> map = new HashMap<>();
    private int maxKeys;
    private EvictionPolicy policy = EvictionPolicy.NOEVICTION;

    public void setMaxKeys(int maxKeys) { this.maxKeys = maxKeys; }
    public int getMaxKeys() { return maxKeys; }
    public void setEvictionPolicy(EvictionPolicy policy) { this.policy = policy; }
    public EvictionPolicy getEvictionPolicy() { return policy; }

    public int size() { return map.size(); }

    public String evictIfNeeded() {
        if (maxKeys <= 0 || map.size() < maxKeys) return null;
        if (policy == EvictionPolicy.NOEVICTION) {
            return "ERR command not allowed when used memory > 'maxkeys'";
        }
        String victim = policy.selectVictim(map.keySet());
        if (victim != null) map.remove(victim);
        return null;
    }

    public void set(String key, byte[] value) {
        map.put(key, new Value(RedisObject.string(value), -1));
    }

    public void setex(String key, byte[] value, long ttlMillis) {
        map.put(key, new Value(RedisObject.string(value), System.currentTimeMillis() + ttlMillis));
    }

    public RedisObject set(String key, RedisObject obj) {
        map.put(key, new Value(obj, -1));
        return obj;
    }

    public RedisObject setex(String key, RedisObject obj, long ttlMillis) {
        map.put(key, new Value(obj, System.currentTimeMillis() + ttlMillis));
        return obj;
    }

    public byte[] get(String key) {
        Value v = map.get(key);
        if (v == null) return null;
        if (v.isExpired()) { map.remove(key); return null; }
        if (v.data.type() != RedisType.STRING) return null;
        return v.data.stringBytes();
    }

    public RedisType getType(String key) {
        Value v = map.get(key);
        if (v == null || v.isExpired()) { return null; }
        return v.data.type();
    }

    public RedisObject getObject(String key) {
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

    public List<Entry> allEntries() {
        List<Entry> entries = new ArrayList<>();
        for (var e : map.entrySet()) {
            Value v = e.getValue();
            if (!v.isExpired()) {
                entries.add(Entry.fromMapEntry(e.getKey(), v.data, v.expiresAt));
            }
        }
        return entries;
    }
}
