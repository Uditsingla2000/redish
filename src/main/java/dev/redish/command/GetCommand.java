package dev.redish.command;

import java.util.List;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;

public class GetCommand implements Command {

    private final Store store;

    public GetCommand(Store store) { this.store = store; }

    @Override
    public Object execute(List<Object> args) {
        if (args.size() != 2) {
            return new ErrorResponse("ERR wrong number of arguments for 'GET' command");
        }
        return store.get((String) args.get(1));
    }
}
