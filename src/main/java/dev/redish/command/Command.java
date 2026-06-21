package dev.redish.command;

import java.util.List;

public interface Command {
    Object execute(List<Object> args);
    default boolean isWrite() { return false; }
}
