package dev.redish.store;

import java.nio.charset.StandardCharsets;

public class RedisObject {

    private final RedisType type;
    private final RedisEncoding encoding;
    private final Object value;

    public RedisObject(RedisType type, RedisEncoding encoding, Object value) {
        this.type = type;
        this.encoding = encoding;
        this.value = value;
    }

    public static RedisObject string(byte[] data) {
        return new RedisObject(RedisType.STRING, data.length <= 44 ? RedisEncoding.EMBSTR : RedisEncoding.RAW, data);
    }

    public static RedisObject string(long n) {
        return new RedisObject(RedisType.STRING, RedisEncoding.INT, n);
    }

    public RedisType type() { return type; }

    public RedisEncoding encoding() { return encoding; }

    public Object value() { return value; }

    public boolean isType(RedisType t) { return type == t; }

    public byte[] stringBytes() {
        if (type != RedisType.STRING) {
            throw new IllegalStateException("not a string");
        }
        if (encoding == RedisEncoding.INT) {
            return Long.toString((long) value).getBytes(StandardCharsets.UTF_8);
        }
        return (byte[]) value;
    }

    public String stringValue() {
        return new String(stringBytes(), StandardCharsets.UTF_8);
    }

    public long longValue() {
        if (encoding == RedisEncoding.INT) return (long) value;
        return Long.parseLong(stringValue());
    }

    public boolean canIncr() {
        if (type != RedisType.STRING) return false;
        if (encoding == RedisEncoding.INT) return true;
        try {
            Long.parseLong(stringValue());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
