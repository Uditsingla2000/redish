package dev.redish.command;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import dev.redish.resp.ErrorResponse;
import org.junit.jupiter.api.Test;

class CommandRegistryTest {

    private final CommandRegistry registry = new CommandRegistry();

    @Test
    void getPing() {
        Command cmd = registry.get("PING");
        assertInstanceOf(PingCommand.class, cmd);
    }

    @Test
    void getPingLowercase() {
        Command cmd = registry.get("ping");
        assertInstanceOf(PingCommand.class, cmd);
    }

    @Test
    void getUnknown() {
        Command cmd = registry.get("FOO");
        assertInstanceOf(UnknownCommand.class, cmd);
    }

    @Test
    void unknownCommandResponse() {
        Command cmd = registry.get("FOO");
        Object result = cmd.execute(List.of("FOO"));
        assertInstanceOf(ErrorResponse.class, result);
        assertEquals("ERR unknown command 'FOO'",
                ((ErrorResponse) result).message());
    }
}
