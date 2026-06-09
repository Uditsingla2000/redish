# impl.md — I/O multiplexing (single-threaded concurrency)

## Why

Current `Server.java` accepts one client, handles it, then loops back to `accept()`. If that client never sends data, nobody else connects. We need many clients in one thread.

## What is I/O multiplexing

One thread monitors many file descriptors (sockets) and only works on the ones that are "ready" — readable (data arrived), writable (buffer has room), or acceptable (new connection).

On your machine (macOS) this is **kqueue**. On Linux it's **epoll**. Java's `java.nio.channels.Selector` wraps whichever the OS provides.

```
Without multiplexing:
accept() → blocks until one client connects
read()   → blocks until that client sends data
write()  → blocks until buffer drains

With multiplexing:
Selector.select() → blocks until *any* channel is ready
    → returns set of ready channels
    → process each: accept(), read(), or write()
```

## Java NIO primitives

| Class | Role |
|---|---|
| `ServerSocketChannel` | The listening socket (one per server) |
| `SocketChannel` | A connected client socket (one per client) |
| `Selector` | The multiplexer — monitors all channels |
| `SelectionKey` | Represents a registered channel + interest ops + attachment |
| `ByteBuffer` | Buffer for reading/writing bytes |

## The event loop

```java
Selector selector = Selector.open();
ServerSocketChannel ssc = ServerSocketChannel.open();
ssc.bind(new InetSocketAddress(PORT));
ssc.configureBlocking(false);
ssc.register(selector, SelectionKey.OP_ACCEPT);

while (true) {
    selector.select();
    var it = selector.selectedKeys().iterator();
    while (it.hasNext()) {
        SelectionKey key = it.next();
        it.remove();
        if (key.isAcceptable())  handleAccept(key);
        if (key.isReadable())    handleRead(key);
        if (key.isWritable())    handleWrite(key);
    }
}
```

## Per-connection state machine

```
ACCEPT → attach ConnectionState, register for OP_READ
           ↓
READ   → read bytes into readBuf, flip, parse().
           ↓ (partial frame → null)   ↓ (full frame → command)
        position(start), compact        execute → serialize → register OP_WRITE
           ↓                              ↓
        (wait for more data)           WRITE → drain writeBuf to channel
                                           ↓ (drained)  ↓ (not drained)
                                        register OP_READ  stay OP_WRITE

(on -1 read or error): cancel key, close channel
```

---

## Step 1: ConnectionState.java

```java
package dev.redish;

import java.nio.ByteBuffer;

class ConnectionState {
    static final int READ_BUF_SIZE = 8192;
    static final int WRITE_BUF_SIZE = 65536;

    ByteBuffer readBuf = ByteBuffer.allocate(READ_BUF_SIZE);
    ByteBuffer writeBuf = ByteBuffer.allocate(WRITE_BUF_SIZE);
}
```

---

## Step 2: RespParser.parse(ByteBuffer) — FINAL SPEC

### Core rule

**Every method saves `int start = buf.position()` on entry.** If the data is incomplete, restore `buf.position(start)` and return `null`. This makes the parser fully idempotent — retrying after more data arrives re-parses from scratch, always correct.

```java
public static Object parse(ByteBuffer buf) {
    if (buf.remaining() == 0) return null;
    byte first = buf.get(buf.position());
    return switch ((char) first) {
        case '+' -> parseSimpleString(buf);
        case '-' -> parseError(buf);
        case ':' -> parseInteger(buf);
        case '$' -> parseBulkString(buf);
        case '*' -> parseArray(buf);
        default -> throw new RespException("Unknown RESP type: " + (char) first);
    };
}
```

### parseLine — reads a `\r\n` terminated line

Scans with `buf.get(i)` (does not advance position). Only consumes bytes on a full match.

```java
private static String parseLine(ByteBuffer buf) {
    int start = buf.position();
    for (int i = start; i < buf.limit(); i++) {
        if (buf.get(i) == '\r' && i + 1 < buf.limit() && buf.get(i + 1) == '\n') {
            byte[] line = new byte[i - start];
            buf.get(line);
            buf.get(); // \r
            buf.get(); // \n
            return new String(line, StandardCharsets.UTF_8);
        }
    }
    buf.position(start);
    return null;
}
```

### Parse methods for each RESP type

```java
private static String parseSimpleString(ByteBuffer buf) {
    buf.get(); // consume '+'
    return parseLine(buf);
}

private static String parseError(ByteBuffer buf) {
    buf.get(); // consume '-'
    return parseLine(buf);
}

private static Long parseInteger(ByteBuffer buf) {
    buf.get(); // consume ':'
    String line = parseLine(buf);
    if (line == null) return null;
    return Long.parseLong(line);
}

private static String parseBulkString(ByteBuffer buf) {
    int start = buf.position();
    buf.get(); // consume '$'
    String lenStr = parseLine(buf);
    if (lenStr == null) { buf.position(start); return null; }
    int len = Integer.parseInt(lenStr);
    if (len == -1) return null;
    if (buf.remaining() < len + 2) { buf.position(start); return null; }
    byte[] data = new byte[len];
    buf.get(data);
    buf.get(); // \r
    buf.get(); // \n
    return new String(data, StandardCharsets.UTF_8);
}

private static List<Object> parseArray(ByteBuffer buf) {
    int start = buf.position();
    buf.get(); // consume '*'
    String lenStr = parseLine(buf);
    if (lenStr == null) { buf.position(start); return null; }
    int len = Integer.parseInt(lenStr);
    if (len == -1) return null;
    List<Object> list = new ArrayList<>(len);
    for (int i = 0; i < len; i++) {
        Object element = parse(buf);
        if (element == null) { buf.position(start); return null; }
        list.add(element);
    }
    return list;
}
```

### Design rationale

- `parseLine` uses `buf.get(i)` peek — position never advances on null.
- `parseBulkString` and `parseArray` both save position before consuming the type byte, restore on null.
- If array element 2/3 is incomplete, `buf.position(start)` rewinds past the `*` line too. On retry, the whole array re-parses from the original `*`. All bytes are still in the buffer (nothing was compacted), so it works.
- **No `mark()`/`reset()`** — plain `position()` save/restore everywhere.
- **Exception rules**: `RespException` only for malformed data (bad int, unknown type byte, etc.). `null` means "need more bytes" — never throw for that.
- Keep old `InputStream` methods — Client.java and tests still use them.

---

## Step 3: RespSerializer.serialize(Object, ByteBuffer)

Appends to write-mode buffer (position = write cursor). Each write method returns the buffer (possibly reallocated if capacity was exceeded).

```java
public static ByteBuffer serialize(Object obj, ByteBuffer buf) {
    if (obj == null) {
        return writeNullBulkString(buf);
    } else if (obj instanceof ErrorResponse e) {
        return writeError(e.message(), buf);
    } else if (obj instanceof String s) {
        if (s.contains("\r") || s.contains("\n")) {
            return writeBulkString(s, buf);
        }
        return writeSimpleString(s, buf);
    } else if (obj instanceof Long l) {
        return writeInteger(l, buf);
    } else if (obj instanceof List<?> list) {
        return writeArray(list, buf);
    }
    return writeBulkString(obj.toString(), buf);
}

private static ByteBuffer writeSimpleString(String s, ByteBuffer buf) {
    buf = grow(buf, 1 + s.length() + 2);
    buf.put((byte) '+');
    buf.put(s.getBytes(StandardCharsets.UTF_8));
    buf.put((byte) '\r');
    buf.put((byte) '\n');
    return buf;
}

private static ByteBuffer writeError(String msg, ByteBuffer buf) {
    buf = grow(buf, 1 + msg.length() + 2);
    buf.put((byte) '-');
    buf.put(msg.getBytes(StandardCharsets.UTF_8));
    buf.put((byte) '\r');
    buf.put((byte) '\n');
    return buf;
}

private static ByteBuffer writeInteger(long value, ByteBuffer buf) {
    String s = Long.toString(value);
    buf = grow(buf, 1 + s.length() + 2);
    buf.put((byte) ':');
    buf.put(s.getBytes(StandardCharsets.UTF_8));
    buf.put((byte) '\r');
    buf.put((byte) '\n');
    return buf;
}

private static ByteBuffer writeBulkString(String s, ByteBuffer buf) {
    byte[] data = s.getBytes(StandardCharsets.UTF_8);
    buf = grow(buf, 1 + lenStr(data.length) + 2 + data.length + 2);
    buf.put((byte) '$');
    buf.put(lenStr(data.length).getBytes(StandardCharsets.UTF_8));
    buf.put((byte) '\r');
    buf.put((byte) '\n');
    buf.put(data);
    buf.put((byte) '\r');
    buf.put((byte) '\n');
    return buf;
}

private static ByteBuffer writeNullBulkString(ByteBuffer buf) {
    buf = grow(buf, 5);
    buf.put((byte) '$');
    buf.put((byte) '-');
    buf.put((byte) '1');
    buf.put((byte) '\r');
    buf.put((byte) '\n');
    return buf;
}

private static ByteBuffer writeArray(List<?> list, ByteBuffer buf) {
    if (list == null) return writeNullBulkString(buf);
    buf = grow(buf, 2 + lenStr(list.size()) + 2);
    buf.put((byte) '*');
    buf.put(lenStr(list.size()).getBytes(StandardCharsets.UTF_8));
    buf.put((byte) '\r');
    buf.put((byte) '\n');
    for (Object elem : list) {
        buf = serialize(elem, buf);
    }
    return buf;
}

/** Double capacity until at least `needed` bytes fit. */
private static ByteBuffer grow(ByteBuffer buf, int needed) {
    if (buf.remaining() >= needed) return buf;
    int newCap = buf.capacity();
    while (buf.position() + needed > newCap) {
        newCap = Math.max(newCap * 2, 64);
    }
    ByteBuffer bigger = ByteBuffer.allocate(newCap);
    buf.flip();
    bigger.put(buf);
    return bigger;
}

private static String lenStr(int len) {
    return Integer.toString(len);
}
```

Caller in Server.java updates its state reference:

```java
ConnectionState st = (ConnectionState) key.attachment();
st.writeBuf = RespSerializer.serialize(result, st.writeBuf);
```

Now any response size works — buffer grows as needed. No crash on large values.

---

## Step 4: RespException.java

```java
package dev.redish.resp;

public class RespException extends RuntimeException {
    public RespException(String message) {
        super(message);
    }
}
```

---

## Step 5: Server.java — full event loop

```java
package dev.redish;

import dev.redish.command.CommandRegistry;
import dev.redish.resp.ErrorResponse;
import dev.redish.resp.RespException;
import dev.redish.resp.RespParser;
import dev.redish.resp.RespSerializer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.List;

public class Server {
    private static final int PORT = 6380;
    private final CommandRegistry registry = new CommandRegistry();

    public void start() throws IOException {
        Selector selector = Selector.open();
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.bind(new InetSocketAddress(PORT));
        ssc.configureBlocking(false);
        ssc.register(selector, SelectionKey.OP_ACCEPT);
        System.out.println("[SERVER] Listening on port " + PORT);

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
                    System.out.println("[SERVER] IO error: " + e.getMessage());
                    closeConnection(key);
                }
            }
        }
    }

    private void handleAccept(Selector selector, ServerSocketChannel ssc) throws IOException {
        SocketChannel client = ssc.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ, new ConnectionState());
        System.out.println("[SERVER] Client connected: " + client.getRemoteAddress());
    }

    @SuppressWarnings("unchecked")
    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ConnectionState st = (ConnectionState) key.attachment();

        int n = ch.read(st.readBuf);
        if (n == -1) {
            closeConnection(key);
            return;
        }

        st.readBuf.flip();
        int start = st.readBuf.position();

        Object parsed;
        try {
            parsed = RespParser.parse(st.readBuf);
        } catch (RespException e) {
            st.writeBuf = RespSerializer.serialize(new ErrorResponse("ERR " + e.getMessage()), st.writeBuf);
            st.readBuf.compact();
            key.interestOps(SelectionKey.OP_WRITE);
            return;
        }

        if (parsed == null) {
            st.readBuf.position(start);
            st.readBuf.compact();
            return;
        }

        if (!(parsed instanceof List<?> args) || args.isEmpty()) {
            st.writeBuf = RespSerializer.serialize(new ErrorResponse("ERR invalid command format"), st.writeBuf);
            st.readBuf.compact();
            key.interestOps(SelectionKey.OP_WRITE);
            return;
        }

        String cmdName = ((String) args.get(0)).toUpperCase();
        var command = registry.get(cmdName);
        @SuppressWarnings("unchecked")
        List<Object> cmdArgs = (List<Object>) args;
        Object result = command.execute(cmdArgs);
        st.writeBuf = RespSerializer.serialize(result, st.writeBuf);

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
            System.out.println("[SERVER] Client disconnected: " + ch.getRemoteAddress());
            ch.close();
        }
    }

    public static void main(String[] args) throws IOException {
        new Server().start();
    }
}
```

### Key details

- `parsed == null` → restore position, compact, return. Buffer stays in write mode for next read.
- `parsed instanceof List<?> args` → client must send RESP array.
- `RespException` caught → serialize error response, switch to OP_WRITE.
- `command.execute(args)` — args includes command name at index 0, same contract as before.
- `registry.get(name)` maps name → handler, falls back to UnknownCommand automatically.
- `handleWrite` → flip (read mode), `channel.write()` may not drain all, compact rest.
- `closeConnection` → cancel key + close channel, used for both disconnect and errors.

---

## Step 6: Edge cases

| Case | Handling |
|---|---|
| Partial RESP frame | Parser returns null → compact, wait for more data |
| Client disconnect | `read()` returns -1 → cancel key, close channel |
| Garbage from client | `RespException` → write error response, switch to OP_WRITE |
| Write buffer full | `grow()` reallocates — never overflows |
| Multiple pending accepts | Loop `ssc.accept()` until null |

---

## Summary of files

| File | Action |
|---|---|
| `src/main/java/dev/redish/ConnectionState.java` | Create |
| `src/main/java/dev/redish/resp/RespException.java` | Create |
| `src/main/java/dev/redish/resp/RespParser.java` | Add `parse(ByteBuffer)` overload (keep old method) |
| `src/main/java/dev/redish/resp/RespSerializer.java` | Add `serialize(Object, ByteBuffer)` overload (keep old method) |
| `src/main/java/dev/redish/Server.java` | Rewrite with NIO event loop |

## Implementation order

1. **RespParser.parse(ByteBuffer)** — with idempotent rewind-on-null
2. **RespSerializer.serialize(Object, ByteBuffer)** — buffer append methods
3. **ConnectionState + RespException** — simple helper classes
4. **Server.java** — NIO event loop
5. **Manual test** — rserver + 3 rcli/telnet clients
