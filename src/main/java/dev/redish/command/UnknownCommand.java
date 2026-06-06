package dev.redish.command;

import java.util.List;

import dev.redish.resp.ErrorResponse;

public class UnknownCommand implements Command {

    @Override
    public Object execute(List<Object> args) {
        String cmd = (String) args.get(0);
        return new ErrorResponse("ERR unknown command '" + cmd + "'");
    }
}
