package dev.redish.command;

import java.util.List;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;

public class DelCommand implements Command {

    private final Store store;

    public DelCommand(Store store) { this.store = store; }

    @Override
    public Object execute(List<Object> args) {
        if (args.size() < 2) {
            return new ErrorResponse("ERR wrong number of arguments for 'DEL' command");
        }
        long count = 0;
        for (int i = 1; i < args.size(); i++) {
            count += store.del((String) args.get(i));
        }
        return count;
    }

    @Override
    public boolean isWrite() { return true; }
}
