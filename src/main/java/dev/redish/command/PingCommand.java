package dev.redish.command;

import java.nio.charset.StandardCharsets;
import java.util.List;

import dev.redish.resp.ErrorResponse;

public class PingCommand implements Command {

    @Override
    public Object execute(List<Object> args) {
        if (args.size() == 1) {
            return "PONG";
        }
        if (args.size() == 2) {
            return ((String) args.get(1)).getBytes(StandardCharsets.UTF_8);
        }
        return new ErrorResponse("ERR wrong number of arguments for 'PING' command");
    }
}
