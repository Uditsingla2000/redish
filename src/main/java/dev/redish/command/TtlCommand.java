package dev.redish.command;

import java.util.List;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;

public class TtlCommand implements Command {

    private final Store store;

    public TtlCommand(Store store) { this.store = store; }

    @Override
    public Object execute(List<Object> args) {
        if (args.size() != 2) {
            return new ErrorResponse("ERR wrong number of arguments for 'TTL' command");
        }
        return store.ttl((String) args.get(1));
    }
}
