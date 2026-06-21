package dev.redish;

import dev.redish.aof.AofRecovery;
import dev.redish.aof.AofRewrite;
import dev.redish.aof.AofWriter;
import dev.redish.command.Command;
import dev.redish.command.CommandRegistry;
import dev.redish.resp.ErrorResponse;
import dev.redish.resp.RespException;
import dev.redish.resp.RespParser;
import dev.redish.resp.RespSerializer;
import dev.redish.store.Store;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;

public class Server {
    private static final int PORT = 6380;
    private final Store store = new Store();
    private final CommandRegistry registry = new CommandRegistry(store);
    private final Config config;
    private final AofWriter aofWriter;
    private boolean aofWriteError;
    private long lastRewriteSize;

    public Server(String[] args) throws IOException {
        this.config = new Config(args);
        if (config.isAofEnabled()) {
            Path aofDir = config.getAofPath().getParent();
            if (aofDir != null) Files.createDirectories(aofDir);
            if (config.isAofRecoverOnStartup() && Files.exists(config.getAofPath())) {
                Log.info("Replaying AOF: " + config.getAofPath());
                AofRecovery.recover(config.getAofPath(), store, registry);
                Log.info("AOF replay complete");
            }
            this.aofWriter = new AofWriter(config.getAofPath(), config.getAofFsync());
            this.lastRewriteSize = aofWriter.size();
        } else {
            this.aofWriter = null;
        }
    }

    public void start() throws IOException {
        Selector selector = Selector.open();
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.bind(new InetSocketAddress(PORT));
        ssc.configureBlocking(false);
        ssc.register(selector, SelectionKey.OP_ACCEPT);

        Logo.print();
        System.out.println("─".repeat(48));
        System.out.printf("  Server listening on port %d%n", PORT);
        System.out.println("─".repeat(48));
        Log.info("Server started on port " + PORT);

        if (aofWriter != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    aofWriter.flush();
                    aofWriter.close();
                } catch (IOException e) {
                    Log.info("AOF flush on shutdown: " + e.getMessage());
                }
            }));
        }

        while (true) {
            selector.select();
            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
            while (it.hasNext()) {
                SelectionKey key = it.next();
                it.remove();
                try {
                    if (key.isAcceptable()) {
                        handleAccept(selector, ssc);
                    } else if (key.isReadable()) {
                        handleRead(key);
                    } else if (key.isWritable()) {
                        handleWrite(key);
                    }
                } catch (IOException e) {
                    Log.info("IO error: " + e.getMessage());
                    closeConnection(key);
                }
            }
            if (aofWriter != null) {
                tickAof();
            }
        }
    }

    private void tickAof() throws IOException {
        String policy = aofWriter.fsyncPolicy();
        if ("always".equals(policy)) {
            aofWriter.flush();
            aofWriter.fsync();
        } else {
            aofWriter.flush();
            if ("everysec".equals(policy) && System.currentTimeMillis() - aofWriter.lastFsync() >= 1000) {
                aofWriter.fsync();
            }
        }
    }

    private void appendAof(List<Object> cmdArgs) throws IOException {
        if (aofWriter == null || aofWriteError) return;
        try {
            ByteBuffer aofCmd = RespSerializer.serialize(cmdArgs, ByteBuffer.allocate(64));
            aofCmd.flip();
            aofWriter.append(aofCmd);
            checkRewrite();
        } catch (IOException e) {
            aofWriteError = true;
            Log.info("AOF write error: " + e.getMessage());
        }
    }

    private void checkRewrite() throws IOException {
        long size = aofWriter.size();
        long minSize = config.getRewriteMinSize();
        long pct = config.getRewritePercentage();
        if (size > minSize && size > lastRewriteSize * (1 + pct / 100.0)) {
            Log.info("AOF rewrite triggered (size=" + size + ")");
            AofRewrite.rewrite(store, config.getAofPath());
            aofWriter.truncate(0);
            lastRewriteSize = 0;
            Log.info("AOF rewrite complete");
        }
    }

    private void handleAccept(Selector selector, ServerSocketChannel ssc) throws IOException {
        SocketChannel client;
        while ((client = ssc.accept()) != null) {
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ, new ConnectionState());
            Log.info("Client connected: " + client.getRemoteAddress());
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ConnectionState st = (ConnectionState) key.attachment();

        int n = ch.read(st.readBuf);
        if (n == -1) {
            closeConnection(key);
            return;
        }

        st.readBuf.flip();

        int processed = 0;
        while (processed++ < 5000) {
            int start = st.readBuf.position();
            Object parsed;

            try {
                parsed = RespParser.parse(st.readBuf);
            } catch (RespException e) {
                st.writeBuf = RespSerializer.serialize(
                    new ErrorResponse("ERR " + e.getMessage()), st.writeBuf);
                st.readBuf.compact();
                key.interestOps(SelectionKey.OP_WRITE);
                return;
            }

            if (parsed == null) {
                st.readBuf.position(start);
                break;
            }

            if (!(parsed instanceof List<?> args) || args.isEmpty()) {
                st.writeBuf = RespSerializer.serialize(
                    new ErrorResponse("ERR expected array"), st.writeBuf);
                break;
            }

            String cmdName = ((String) args.get(0)).toUpperCase();
            Command cmd = registry.get(cmdName);
            @SuppressWarnings("unchecked")
            List<Object> cmdArgs = (List<Object>) args;
            Object result = cmd.execute(cmdArgs);
            if (cmd.isWrite()) appendAof(cmdArgs);
            st.writeBuf = RespSerializer.serialize(result, st.writeBuf);
        }

        st.readBuf.compact();
        key.interestOps(SelectionKey.OP_WRITE);
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ConnectionState st = (ConnectionState) key.attachment();

        st.writeBuf.flip();
        ch.write(st.writeBuf);

        if (st.writeBuf.hasRemaining()) {
            st.writeBuf.compact();
        } else {
            st.writeBuf.clear();
            key.interestOps(SelectionKey.OP_READ);
        }
    }

    private void closeConnection(SelectionKey key) throws IOException {
        key.cancel();
        if (key.channel() instanceof SocketChannel ch) {
            Log.info("Client disconnected: " + ch.getRemoteAddress());
            ch.close();
        }
    }

    public static void main(String[] args) throws IOException {
        new Server(args).start();
    }
}
