package dev.redish.command;

import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.RedisObject;
import dev.redish.store.RedisType;
import dev.redish.store.Store;

public class IncrCommand implements Command {

    private final Store store;

    public IncrCommand(Store store) { this.store = store; }

    @Override
    public Object execute(List<Object> args) {
        if (args.size() != 2) {
            return new ErrorResponse("ERR wrong number of arguments for 'INCR' command");
        }

        String key = (String) args.get(1);
        RedisObject obj = store.getObject(key);

        if (obj == null) {
            store.set(key, RedisObject.string(1L));
            return 1L;
        }

        if (!obj.isType(RedisType.STRING)) {
            return new ErrorResponse("WRONGTYPE Operation against a key holding the wrong kind of value");
        }

        if (!obj.canIncr()) {
            return new ErrorResponse("ERR value is not an integer or out of range");
        }

        long n = obj.longValue() + 1;
        store.set(key, RedisObject.string(n));
        return n;
    }

    @Override
    public boolean isWrite() { return true; }
}
