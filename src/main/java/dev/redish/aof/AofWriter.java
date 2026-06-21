package dev.redish.aof;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class AofWriter {

    private final FileChannel channel;
    private final ByteBuffer buf = ByteBuffer.allocateDirect(8192);
    private final String fsyncPolicy;
    private long lastFsync = System.currentTimeMillis();

    public AofWriter(Path path, String fsyncPolicy) throws IOException {
        this.channel = FileChannel.open(path,
            StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        this.fsyncPolicy = fsyncPolicy;
    }

    public void append(ByteBuffer data) throws IOException {
        while (data.hasRemaining()) {
            int toWrite = Math.min(data.remaining(), buf.remaining());
            for (int i = 0; i < toWrite; i++) {
                buf.put(data.get());
            }
            if (!buf.hasRemaining()) {
                flush();
            }
        }
    }

    public void flush() throws IOException {
        buf.flip();
        while (buf.hasRemaining()) {
            channel.write(buf);
        }
        buf.clear();
    }

    public void fsync() throws IOException {
        channel.force(true);
        lastFsync = System.currentTimeMillis();
    }

    public long lastFsync() { return lastFsync; }

    public String fsyncPolicy() { return fsyncPolicy; }

    public long size() throws IOException {
        return channel.size();
    }

    public void truncate(long size) throws IOException {
        flush();
        channel.truncate(size);
    }

    public void close() throws IOException {
        flush();
        channel.close();
    }
}
