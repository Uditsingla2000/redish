package dev.redish.command;

import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;

public class SetCommand implements Command {

    private final Store store;

    public SetCommand(Store store) { this.store = store; }

    @Override
    public Object execute(List<Object> args) {
        if (args.size() < 3 || args.size() == 4 || args.size() > 5) {
            return new ErrorResponse("ERR wrong number of arguments for 'SET' command");
        }

        String key = (String) args.get(1);
        byte[] value = ((String) args.get(2)).getBytes(StandardCharsets.UTF_8);

        if (args.size() == 5) {
            if (!"EX".equalsIgnoreCase((String) args.get(3))) {
                return new ErrorResponse("ERR syntax error");
            }
            try {
                long seconds = Long.parseLong((String) args.get(4));
                if (seconds <= 0) {
                    return new ErrorResponse("ERR invalid expire time in 'SET' command");
                }
                store.setex(key, value, seconds * 1000);
            } catch (NumberFormatException e) {
                return new ErrorResponse("ERR value is not an integer or out of range");
            }
        } else {
            store.set(key, value);
        }
        return "OK";
    }

    @Override
    public boolean isWrite() { return true; }
}
