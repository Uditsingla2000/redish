package dev.redish.aof;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AofWriterTest {

    @TempDir
    Path tmpDir;

    @Test
    void writeAndReadBack() throws IOException {
        Path aof = tmpDir.resolve("test.aof");
        var writer = new AofWriter(aof, "everysec");

        ByteBuffer cmd = ByteBuffer.wrap("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nvalue\r\n".getBytes(StandardCharsets.UTF_8));
        writer.append(cmd);
        writer.flush();

        byte[] bytes = Files.readAllBytes(aof);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(content.contains("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nvalue\r\n"));
        writer.close();
    }

    @Test
    void multipleCommands() throws IOException {
        Path aof = tmpDir.resolve("multi.aof");
        var writer = new AofWriter(aof, "everysec");

        writer.append(ByteBuffer.wrap("*2\r\n$3\r\nGET\r\n$1\r\nx\r\n".getBytes(StandardCharsets.UTF_8)));
        writer.append(ByteBuffer.wrap("*2\r\n$3\r\nDEL\r\n$1\r\ny\r\n".getBytes(StandardCharsets.UTF_8)));
        writer.flush();

        byte[] bytes = Files.readAllBytes(aof);
        String content = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(content.contains("GET"));
        assertTrue(content.contains("DEL"));
        writer.close();
    }

    @Test
    void truncate() throws IOException {
        Path aof = tmpDir.resolve("trunc.aof");
        var writer = new AofWriter(aof, "everysec");

        writer.append(ByteBuffer.wrap("*2\r\n$3\r\nDEL\r\n$1\r\nx\r\n".getBytes(StandardCharsets.UTF_8)));
        writer.flush();
        assertTrue(writer.size() > 0);

        writer.truncate(0);
        assertEquals(0, writer.size());
        writer.close();
    }

    @Test
    void fsyncPolicyAlwaysFsync() throws IOException {
        Path aof = tmpDir.resolve("fsync.aof");
        var writer = new AofWriter(aof, "always");
        assertEquals("always", writer.fsyncPolicy());
        writer.close();
    }
}
