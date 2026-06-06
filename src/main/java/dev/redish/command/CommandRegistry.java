package dev.redish.command;

import java.util.HashMap;
import java.util.Map;

public class CommandRegistry {

    private final Map<String, Command> commands = new HashMap<>();

    public CommandRegistry() {
        register("PING", new PingCommand());
    }

    public void register(String name, Command command) {
        commands.put(name.toUpperCase(), command);
    }

    public Command get(String name) {
        Command cmd = commands.get(name.toUpperCase());
        return cmd != null ? cmd : new UnknownCommand();
    }
}
