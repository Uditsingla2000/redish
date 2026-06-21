package dev.redish.aof;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.redish.command.CommandRegistry;
import dev.redish.resp.RespSerializer;
import dev.redish.store.Store;

class AofRewriteTest {

    @TempDir
    Path tmpDir;

    @Test
    void rewriteCompactsSets() throws IOException {
        Store store = new Store();
        store.set("k", "v1".getBytes());
        store.set("k", "v2".getBytes()); // overwrite

        Path aof = tmpDir.resolve("rewrite.aof");
        AofRewrite.rewrite(store, aof);

        // Should have only one SET command with the final value
        byte[] bytes = Files.readAllBytes(aof);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(content.contains("v2"));
        assertEquals(1, content.split("SET").length - 1, "should have exactly one SET");
    }

    @Test
    void rewriteWithExpiry() throws IOException, InterruptedException {
        Store store = new Store();
        store.setex("tmp", "x".getBytes(), 200);

        Path aof = tmpDir.resolve("expiry.aof");
        AofRewrite.rewrite(store, aof);

        byte[] bytes = Files.readAllBytes(aof);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(content.contains("EX"));
    }

    @Test
    void rewriteEmptyStore() throws IOException {
        Store store = new Store();
        Path aof = tmpDir.resolve("empty.aof");
        AofRewrite.rewrite(store, aof);

        byte[] bytes = Files.readAllBytes(aof);
        assertEquals(0, bytes.length);
    }

    @Test
    void rewrittenFileIsReplayable() throws IOException {
        Store store = new Store();
        store.set("a", "1".getBytes());
        store.set("b", "2".getBytes());
        store.setex("c", "3".getBytes(), 99999);

        Path aof = tmpDir.resolve("replay.aof");
        AofRewrite.rewrite(store, aof);

        // Replay into a fresh store
        Store restored = new Store();
        CommandRegistry registry = new CommandRegistry(restored);
        AofRecovery.recover(aof, restored, registry);

        assertArrayEquals("1".getBytes(), (byte[]) restored.get("a"));
        assertArrayEquals("2".getBytes(), (byte[]) restored.get("b"));
        assertArrayEquals("3".getBytes(), (byte[]) restored.get("c"));
    }
}
