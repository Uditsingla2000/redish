package dev.redish.store;

public enum RedisType {
    STRING,
    LIST,
    SET,
    HASH,
    ZSET;

    public String respName() {
        return name().toLowerCase();
    }
}
