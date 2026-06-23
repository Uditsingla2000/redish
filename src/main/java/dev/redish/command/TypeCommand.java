package dev.redish.command;

import java.util.List;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.RedisType;
import dev.redish.store.Store;

public class TypeCommand implements Command {

    private final Store store;

    public TypeCommand(Store store) { this.store = store; }

    @Override
    public Object execute(List<Object> args) {
        if (args.size() != 2) {
            return new ErrorResponse("ERR wrong number of arguments for 'TYPE' command");
        }

        String key = (String) args.get(1);
        RedisType type = store.getType(key);
        return type != null ? type.respName() : "none";
    }
}
