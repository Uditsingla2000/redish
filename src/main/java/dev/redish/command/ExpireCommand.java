package dev.redish.command;

import java.util.List;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;

public class ExpireCommand implements Command {

    private final Store store;

    public ExpireCommand(Store store) { this.store = store; }

    @Override
    public Object execute(List<Object> args) {
        if (args.size() != 3) {
            return new ErrorResponse("ERR wrong number of arguments for 'EXPIRE' command");
        }
        try {
            long seconds = Long.parseLong((String) args.get(2));
            return store.expire((String) args.get(1), seconds * 1000) ? 1L : 0L;
        } catch (NumberFormatException e) {
            return new ErrorResponse("ERR value is not an integer or out of range");
        }
    }

    @Override
    public boolean isWrite() { return true; }
}
