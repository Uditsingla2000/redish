package dev.redish;

import dev.redish.command.CommandRegistry;
import dev.redish.resp.ErrorResponse;
import dev.redish.resp.RespException;
import dev.redish.resp.RespParser;
import dev.redish.resp.RespSerializer;
import dev.redish.store.Store;

import java.io.IOException;
import java.net.InetSocketAddress;
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
            Object result = registry.get(cmdName).execute((List<Object>) args);
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
        new Server().start();
    }
}
