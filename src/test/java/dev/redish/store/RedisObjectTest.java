package dev.redish.store;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class RedisObjectTest {

    @Test
    void stringFromBytes() {
        RedisObject o = RedisObject.string("hello".getBytes());
        assertEquals(RedisType.STRING, o.type());
        assertEquals(RedisEncoding.EMBSTR, o.encoding());
        assertEquals("hello", o.stringValue());
    }

    @Test
    void stringFromLong() {
        RedisObject o = RedisObject.string(42L);
        assertEquals(RedisType.STRING, o.type());
        assertEquals(RedisEncoding.INT, o.encoding());
        assertEquals(42L, o.longValue());
    }

    @Test
    void longValueFromRawString() {
        RedisObject o = RedisObject.string("123".getBytes());
        assertEquals(123L, o.longValue());
    }

    @Test
    void canIncrIntEncoding() {
        assertTrue(RedisObject.string(99L).canIncr());
    }

    @Test
    void canIncrRawNumeric() {
        assertTrue(RedisObject.string("456".getBytes()).canIncr());
    }

    @Test
    void cannotIncrNonNumeric() {
        assertFalse(RedisObject.string("abc".getBytes()).canIncr());
    }

    @Test
    void isTypeCheck() {
        RedisObject o = RedisObject.string("x".getBytes());
        assertTrue(o.isType(RedisType.STRING));
        assertFalse(o.isType(RedisType.LIST));
    }

    @Test
    void respName() {
        assertEquals("string", RedisType.STRING.respName());
        assertEquals("list", RedisType.LIST.respName());
        assertEquals("none", "none");
    }
}
