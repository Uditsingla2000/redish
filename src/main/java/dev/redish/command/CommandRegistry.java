package dev.redish.command;

import java.util.HashMap;
import java.util.Map;

import dev.redish.store.Store;

public class CommandRegistry {

    private final Map<String, Command> commands = new HashMap<>();

    public CommandRegistry(Store store) {
        register("PING",   new PingCommand());
        register("SET",    new SetCommand(store));
        register("GET",    new GetCommand(store));
        register("TTL",    new TtlCommand(store));
        register("DEL",    new DelCommand(store));
        register("EXPIRE", new ExpireCommand(store));
        register("INCR",   new IncrCommand(store));
        register("TYPE",   new TypeCommand(store));
    }

    public void register(String name, Command command) {
        commands.put(name.toUpperCase(), command);
    }

    public Command get(String name) {
        Command cmd = commands.get(name.toUpperCase());
        return cmd != null ? cmd : new UnknownCommand();
    }
}
