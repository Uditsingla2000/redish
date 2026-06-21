package dev.redish.aof;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.redish.command.CommandRegistry;
import dev.redish.store.Store;

class AofRecoveryTest {

    @TempDir
    Path tmpDir;

    @Test
    void recoverSetAndGet() throws IOException {
        Path aof = tmpDir.resolve("recover.aof");
        Files.writeString(aof, "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nvalue\r\n");

        Store store = new Store();
        CommandRegistry registry = new CommandRegistry(store);
        AofRecovery.recover(aof, store, registry);

        assertArrayEquals("value".getBytes(StandardCharsets.UTF_8), (byte[]) store.get("k"));
    }

    @Test
    void recoverMultipleCommands() throws IOException {
        Path aof = tmpDir.resolve("multi.aof");
        Files.writeString(aof,
            "*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\n1\r\n" +
            "*3\r\n$3\r\nSET\r\n$1\r\nb\r\n$1\r\n2\r\n" +
            "*2\r\n$3\r\nDEL\r\n$1\r\na\r\n");

        Store store = new Store();
        CommandRegistry registry = new CommandRegistry(store);
        AofRecovery.recover(aof, store, registry);

        assertNull(store.get("a"));
        assertArrayEquals("2".getBytes(), (byte[]) store.get("b"));
    }

    @Test
    void recoverTruncatedFile() throws IOException {
        Path aof = tmpDir.resolve("trunc.aof");
        Files.writeString(aof, "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nvalue");

        Store store = new Store();
        CommandRegistry registry = new CommandRegistry(store);
        AofRecovery.recover(aof, store, registry);

        assertNull(store.get("k"));
    }

    @Test
    void recoverNoFile() {
        Path aof = tmpDir.resolve("nonexistent.aof");
        Store store = new Store();
        CommandRegistry registry = new CommandRegistry(store);
        assertThrows(IOException.class, () -> AofRecovery.recover(aof, store, registry));
    }
}
