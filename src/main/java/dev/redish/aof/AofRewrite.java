package dev.redish.aof;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

import dev.redish.resp.RespSerializer;
import dev.redish.store.Store;

public class AofRewrite {

    public static void rewrite(Store store, Path aofPath) throws IOException {
        Path tmp = aofPath.resolveSibling(aofPath.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer buf = ByteBuffer.allocate(8192);
            for (Store.Entry entry : store.allEntries()) {
                List<Object> cmd;
                if (entry.expiresAt() == -1) {
                    cmd = List.of("SET", entry.key(), new String(entry.data()));
                } else {
                    long remainingMs = entry.expiresAt() - System.currentTimeMillis();
                    if (remainingMs <= 0) continue;
                    long seconds = (remainingMs + 999) / 1000;
                    cmd = List.of("SET", entry.key(), new String(entry.data()), "EX", String.valueOf(seconds));
                }
                buf = RespSerializer.serialize(cmd, buf);
                buf.flip();
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                buf.compact();
            }
        }
        Files.move(tmp, aofPath, StandardCopyOption.ATOMIC_MOVE);
    }
}
