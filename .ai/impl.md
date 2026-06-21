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
        SocketChannel client;
        while ((client = ssc.accept()) != null) {
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ, new ConnectionState());
            System.out.println("[SERVER] Client connected: " + client.getRemoteAddress());
        }
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

---

# SET / GET / TTL — in-memory store with expiry

## Wire format

```
SET key value [EX seconds]
  → +OK\r\n
  → -ERR wrong number of arguments for 'SET' command\r\n
  → -ERR value is not an integer or out of range\r\n  (bad EX value)

GET key
  → $<len>\r\n<value>\r\n    (key exists)
  → $-1\r\n                  (key missing or expired)

TTL key
  → :<ttl>\r\n               (positive = seconds remaining)
  → :-1\r\n                  (key has no expiry, or key missing)
```

## Store: `Store.java`

A shared key-value store accessed by all command handlers. Single-threaded event loop means no locks needed.

```java
package dev.redish.store;

import java.util.HashMap;
import java.util.Map;

public class Store {

    private record Value(Object data, long expiresAt) {
        boolean isExpired() { return expiresAt != -1 && System.currentTimeMillis() > expiresAt; }
    }

    private final Map<String, Value> map = new HashMap<>();

    public void set(String key, Object value) {
        map.put(key, new Value(value, -1));
    }

    public void setex(String key, Object value, long ttlMillis) {
        map.put(key, new Value(value, System.currentTimeMillis() + ttlMillis));
    }

    public Object get(String key) {
        Value v = map.get(key);
        if (v == null) return null;
        if (v.isExpired()) { map.remove(key); return null; }
        return v.data;
    }

    public long ttl(String key) {
        Value v = map.get(key);
        if (v == null) return -1;
        if (v.isExpired()) { map.remove(key); return -1; }
        if (v.expiresAt == -1) return -1;
        return (v.expiresAt - System.currentTimeMillis() + 999) / 1000;
    }
}
```

### Expiry strategy: lazy

Check expiration on every `GET`/`TTL`. No background thread. Expired keys are purged on access. This is what Redis does for keys that aren't touched by the active expire cycle.

## Commands

### `SetCommand.java`

```java
package dev.redish.command;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;
import java.util.List;

public class SetCommand implements Command {
    private final Store store;

    public SetCommand(Store store) {
        this.store = store;
    }

    @Override
    public Object execute(List<Object> args) {
        // args = ["SET", "key", "value"] or ["SET", "key", "value", "EX", "seconds"]
        if (args.size() < 3 || args.size() == 4 || args.size() > 5) {
            return new ErrorResponse("ERR wrong number of arguments for 'SET' command");
        }

        String key = (String) args.get(1);
        byte[] value = ((String) args.get(2)).getBytes(StandardCharsets.UTF_8);

        if (args.size() == 5) {
            if (!"EX".equalsIgnoreCase((String) args.get(3))) {
                return new ErrorResponse("ERR syntax error");
            }
            try {
                long seconds = Long.parseLong((String) args.get(4));
                if (seconds <= 0) {
                    return new ErrorResponse("ERR invalid expire time in 'SET' command");
                }
                store.setex(key, value, seconds * 1000);
            } catch (NumberFormatException e) {
                return new ErrorResponse("ERR value is not an integer or out of range");
            }
        } else {
            store.set(key, value);
        }
        return "OK";
    }
}
```

### `GetCommand.java`

```java
package dev.redish.command;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;
import java.util.List;

public class GetCommand implements Command {
    private final Store store;

    public GetCommand(Store store) { this.store = store; }

    @Override
    public Object execute(List<Object> args) {
        if (args.size() != 2) {
            return new ErrorResponse("ERR wrong number of arguments for 'GET' command");
        }
        String key = (String) args.get(1);
        return store.get(key); // null → $-1, byte[] → bulk string
    }
}
```

### `TtlCommand.java`

```java
package dev.redish.command;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;
import java.util.List;

public class TtlCommand implements Command {
    private final Store store;

    public TtlCommand(Store store) { this.store = store; }

    @Override
    public Object execute(List<Object> args) {
        if (args.size() != 2) {
            return new ErrorResponse("ERR wrong number of arguments for 'TTL' command");
        }
        String key = (String) args.get(1);
        return store.ttl(key); // returns Long
    }
}
```

### Register in `CommandRegistry`

`CommandRegistry` needs to accept a `Store` instance and register the new commands:

```java
public CommandRegistry(Store store) {
    register("PING", new PingCommand());
    register("SET",  new SetCommand(store));
    register("GET",  new GetCommand(store));
    register("TTL",  new TtlCommand(store));
}
```

The existing no-arg constructor can default to a new Store for backward compat, or just update the one usage in Server.java.

### Update `Server.java`

```java
private final Store store = new Store();
private final CommandRegistry registry = new CommandRegistry(store);
```

## What RESP type to return

| Command | Returns |
|---|---|
| SET OK | `String "OK"` → `+OK\r\n` |
| SET error | `ErrorResponse` → `-ERR ...\r\n` |
| GET hit | `byte[]` → `$<len>\r\n...\r\n` |
| GET miss | `null` → `$-1\r\n` |
| TTL | `Long` → `:<ttl>\r\n` |

The serializer already handles all these types.

## File changes

| File | Action |
|---|---|
| `src/main/java/dev/redish/store/Store.java` | Create |
| `src/main/java/dev/redish/command/SetCommand.java` | Create |
| `src/main/java/dev/redish/command/GetCommand.java` | Create |
| `src/main/java/dev/redish/command/TtlCommand.java` | Create |
| `src/main/java/dev/redish/command/CommandRegistry.java` | Add `Store` param, register new commands |
| `src/main/java/dev/redish/Server.java` | Create `Store`, pass to `CommandRegistry` |
| `src/test/java/dev/redish/store/StoreTest.java` | Create (tests for set/get/ttl/expiry) |
| `src/test/java/dev/redish/command/SetCommandTest.java` | Create |
| `src/test/java/dev/redish/command/GetCommandTest.java` | Create |
| `src/test/java/dev/redish/command/TtlCommandTest.java` | Create |

## Edge cases

| Case | Handling |
|---|---|
| SET with EX 0 | Error: "invalid expire time in 'SET' command" |
| SET with EX -1 | Error (parsed as invalid) |
| SET with EX "abc" | Error: "value is not an integer or out of range" |
| SET without value | Error: "wrong number of arguments" |
| GET expired key | `null` → `$-1\r\n` (lazy expiry) |
| GET nonexistent key | `null` → `$-1\r\n` |
| TTL on key with no expiry | `-1` |
| TTL on expired/missing key | `-1` |
| TTL on key with expiry | Seconds remaining (rounded up) |
| SET twice (overwrite) | New value + new expiry if EX given |

## Testing strategy

- `StoreTest`: unit test store in isolation (set/get/ttl/expiry)
- `SetCommandTest`: verify args parsing, EX option, error responses
- `GetCommandTest`: hit/miss/wrong-args
- `TtlCommandTest`: no-expiry/expired/remaining
- Manual: `rcli` with `SET key value EX 2` → `GET key` → wait 3s → `GET key` (should return null)

---

# DEL / EXPIRE — key deletion and expiry management

## Wire format

```
DEL key [key ...]
  → :<count>\r\n    (number of keys deleted)

EXPIRE key seconds
  → :1\r\n          (expiry set)
  → :0\r\n          (key does not exist)
  → -ERR wrong number of arguments for 'EXPIRE' command\r\n
  → -ERR value is not an integer or out of range\r\n
```

## Store changes

Add two methods to `Store.java`:

```java
public long del(String key) {
    Value v = map.remove(key);
    return v != null && !v.isExpired() ? 1 : 0;
}

public long del(List<String> keys) {
    return keys.stream().mapToLong(this::del).sum();
}

public boolean expire(String key, long ttlMillis) {
    Value v = map.get(key);
    if (v == null || v.isExpired()) return false;
    map.put(key, new Value(v.data, System.currentTimeMillis() + ttlMillis));
    return true;
}
```

Note: `del` checks `isExpired()` so already-expired keys count as not deleted (returns 0).

## Commands

### `DelCommand.java`

```java
package dev.redish.command;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;
import java.util.List;
import java.util.ArrayList;

public class DelCommand implements Command {
    private final Store store;
    public DelCommand(Store store) { this.store = store; }

    @Override
    public Object execute(List<Object> args) {
        if (args.size() < 2) {
            return new ErrorResponse("ERR wrong number of arguments for 'DEL' command");
        }
        long count = 0;
        for (int i = 1; i < args.size(); i++) {
            count += store.del((String) args.get(i));
        }
        return count;
    }
}
```

### `ExpireCommand.java`

```java
package dev.redish.command;

import dev.redish.resp.ErrorResponse;
import dev.redish.store.Store;
import java.util.List;

public class ExpireCommand implements Command {
    private final Store store;
    public ExpireCommand(Store store) { this.store = store; }

    @Override
    public Object execute(List<Object> args) {
        if (args.size() != 3) {
            return new ErrorResponse("ERR wrong number of arguments for 'EXPIRE' command");
        }
        try {
            long seconds = Long.parseLong((String) args.get(2));
            return store.expire((String) args.get(1), seconds * 1000) ? 1L : 0L;
        } catch (NumberFormatException e) {
            return new ErrorResponse("ERR value is not an integer or out of range");
        }
    }
}
```

## Register in `CommandRegistry`

```java
register("DEL",    new DelCommand(store));
register("EXPIRE", new ExpireCommand(store));
```

## RESP types returned

| Command | Returns |
|---|---|
| DEL hit | `Long` count → `:<n>\r\n` |
| DEL no keys | `Long 0` → `:0\r\n` |
| EXPIRE set | `Long 1` → `:1\r\n` |
| EXPIRE key missing | `Long 0` → `:0\r\n` |

## File changes

| File | Action |
|---|---|
| `src/main/java/dev/redish/store/Store.java` | Add `del(key)`, `del(keys)`, `expire(key, ttlMillis)` |
| `src/main/java/dev/redish/command/DelCommand.java` | Create |
| `src/main/java/dev/redish/command/ExpireCommand.java` | Create |
| `src/main/java/dev/redish/command/CommandRegistry.java` | Register DEL and EXPIRE |
| `src/test/java/dev/redish/store/StoreTest.java` | Add del/expire tests |
| `src/test/java/dev/redish/command/DelCommandTest.java` | Create |
| `src/test/java/dev/redish/command/ExpireCommandTest.java` | Create |

## Edge cases

| Case | Handling |
|---|---|
| DEL missing key | Returns `0` |
| DEL expired key | Returns `0` (expired counted as not existing) |
| DEL multiple keys | Returns count of non-expired deleted keys |
| DEL no args | Error: wrong number of arguments |
| EXPIRE on missing key | Returns `0` |
| EXPIRE on expired key | Returns `0` (lazy expiry before setting) |
| EXPIRE negative seconds | `Long.parseLong` accepts negative → stored as negative TTL → immediately expired |
| EXPIRE overwrite existing expiry | Replaces old expiry with new one |
| EXPIRE non-integer seconds | Error: not an integer |

---

# Command pipelining — drain loop in handleRead

## The problem

`handleRead` processes exactly **one** command per readable event:

```
read() → flip() → parse() → execute() → serialize() → compact() → OP_WRITE
```

If the client sends 10 commands in one TCP segment, all 10 land in `readBuf`, but only 1 is executed. The remaining 9 sit in `readBuf` while the socket buffer is empty → OP_READ never fires again → those commands are orphaned.

## The fix: drain loop in handleRead

Turn the single-parse into a `while` loop that drains all complete commands from `readBuf` before switching to OP_WRITE:

```
read() → flip()
         while (true):
           save position
           parse()
           if null → restore position, break
           if resp-exception → send error, disconnect, return
           if not array → send error, break
           execute → serialize into writeBuf
           if count ≥ 5000 → break
         compact()
         OP_WRITE
```

## handleRead diff

```java
private void handleRead(SelectionKey key) throws IOException {
    SocketChannel ch = (SocketChannel) key.channel();
    ConnectionState st = (ConnectionState) key.attachment();

    int n = ch.read(st.readBuf);
    if (n == -1) { closeConnection(key); return; }

    st.readBuf.flip();
    int commands = 0;

    while (commands++ < 5000) {
        int start = st.readBuf.position();
        Object parsed;

        try {
            parsed = RespParser.parse(st.readBuf);
        } catch (RespException e) {
            st.writeBuf = RespSerializer.serialize(
                new ErrorResponse("ERR " + e.getMessage()), st.writeBuf);
            st.readBuf.compact();
            key.interestOps(SelectionKey.OP_WRITE);
            return;  // disconnect after write — like Redis
        }

        if (parsed == null) {
            st.readBuf.position(start);
            break;  // partial frame, wait for more data
        }

        if (!(parsed instanceof List<?> args) || args.isEmpty()) {
            st.writeBuf = RespSerializer.serialize(
                new ErrorResponse("ERR expected array"), st.writeBuf);
            break;  // can't recover framing
        }

        String cmdName = ((String) args.get(0)).toUpperCase();
        Object result = registry.get(cmdName).execute((List<Object>) args);
        st.writeBuf = RespSerializer.serialize(result, st.writeBuf);
    }

    st.readBuf.compact();
    key.interestOps(SelectionKey.OP_WRITE);
}
```

## Error behavior

| Error | Where detected | Action |
|---|---|---|
| Protocol error (bad RESP) | `RespException` in parser | Send error, disconnect (like Redis `CLIENT_CLOSE_AFTER_REPLY`) |
| Wrong command args | `Command.execute()` returns `ErrorResponse` | Per-command error in response array, pipeline continues |
| Unknown command | `CommandRegistry` returns `UnknownCommand` | Per-command `-ERR unknown command`, pipeline continues |
| Partial frame at end | `parse()` returns null | Rewind, break drain loop, compact, wait for more data |

## Cap: 5000 commands per read event

Counter stops the drain loop at 5000, preventing unbounded write-buffer growth. Remaining commands in `readBuf` wait for the next write→read cycle.

## File changes

| File | Change |
|---|---|
| `Server.java` | Replace single-parse with while-loop drain in `handleRead` |

## Test plan (new file: `PipelineTest.java`)

| Test | What it verifies |
|---|---|
| `twoCommands` | Send `SET k v` + `GET k` in one write, verify both responses |
| `tenCommands` | 10 pipelined SETs + GETs |
| `partialFrame` | One complete command + trailing partial, only first executes |
| `commandErrorContinues` | Valid cmd + bad-args cmd + valid cmd → all three responses (second is error) |
| `protocolErrorFails` | Malformed RESP → error response |

## What does NOT change

- `RespParser`, `RespSerializer`, `Store`, `Command`, `ConnectionState`, `CommandRegistry` — all untouched
- The wire format for individual commands
- The write path (`handleWrite` stays identical)
- The accept path

---

# AOF persistence — Append-Only File

## Requirements (from user)

| Decision | Choice |
|---|---|
| Enabled by default? | No — `--aof` flag to enable |
| Fsync policy | Config file with Redis defaults (everysec), updateable naming |
| AOF rewrite | Yes — include in this iteration |
| Auto-recover on startup | Configurable via config file |
| File path | `data/appendonly.aof` |
| Configuration style | Startup flags only (no runtime CONFIG SET) |

## What is AOF

Every write command (`SET`, `DEL`, `EXPIRE`) is logged in RESP format to `data/appendonly.aof`. On restart, the file is replayed to reconstruct the dataset. This gives durability beyond the in-memory only store.

## Redis defaults (used when config values are absent)

| Config | Default |
|---|---|
| appendonly | no |
| appendfsync | everysec |
| auto-aof-rewrite-percentage | 100 |
| auto-aof-rewrite-min-size | 64mb |
| aof-load-truncated | yes |

## Config file: `redish.conf`

Java Properties format with comments. Loaded from working directory if present. Command-line flags override file values.

```properties
# AOF persistence
# Enable AOF to log every write command to disk
aof.enabled = false

# Fsync policy: how often to synchronize the AOF file to disk
#   always    — fsync after every write (slowest, safest)
#   everysec  — fsync once per second (Redis default)
#   no        — let OS handle it (fastest, least safe)
aof.fsync = everysec

# Recover state from AOF on server startup
# If true and an AOF file exists, it is replayed before accepting connections
aof.recover-on-startup = true

# AOF rewrite triggers
# Rewrite is triggered when the AOF file grows by this percentage
# relative to the last rewrite size (100 = double the last size)
aof.rewrite.percentage = 100

# Minimum AOF file size (in bytes) before rewrite is considered
# 67108864 = 64 MB
aof.rewrite.min-size = 67108864
```

## Startup flags

Flags override config file values:

```
--aof                        # enable AOF
--aof-fsync <policy>         # override fsync policy
--aof-no-recover             # disable auto-recovery
--aof-dir <path>             # override AOF directory (default data/)
```

## AofWriter.java

Writes RESP-formatted commands to the AOF file. Buffered writes with configurable fsync.

```java
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
    private long fileSize;

    public AofWriter(Path path, String fsyncPolicy) throws IOException { ... }
    public void append(ByteBuffer command) throws IOException { ... }
    public void flush() throws IOException { ... }
    public void fsync() throws IOException { ... }
    public long size() { return fileSize; }
    public void close() throws IOException { ... }
}
```

### `append(ByteBuffer command)`

The command buffer is the raw RESP bytes received from the client (the parsed command before execution). Append bytes to internal buffer, flushing if full.

### `flush()` / `fsync()`

- `flush()` — write buffer to channel
- `fsync()` — `channel.force(true)`, called per `fsyncPolicy`

In the event loop, after every write-command execution, call `append()`. After the write buffer is drained (in `handleWrite`), check if a periodic fsync is needed:

```
if (aofEnabled && now - lastFsync >= 1000ms) { flush(); fsync(); }
```

## Determining write commands

A command is a "write command" if it modifies state. The command implementations know this. Add a method to the `Command` interface:

```java
default boolean isWrite() { return false; }
```

Override in `SetCommand`, `DelCommand`, `ExpireCommand` to return `true`.

In `Server.handleRead`, after executing a command:

```java
if (command.isWrite() && aofEnabled) {
    // Re-serialize the args into RESP and append to AOF
    ByteBuffer aofCmd = RespSerializer.serialize(args, ByteBuffer.allocate(64));
    aofWriter.append(aofCmd);
}
```

The raw client RESP is already in the read buffer but it's consumed by the parser. Instead, re-serialize `args` (the parsed command list) back into RESP wire format using `RespSerializer`.

### Why re-serialize instead of saving raw bytes

The raw bytes are consumed by the parser (position advanced). Saving them would require copying before parsing or using a look-behind buffer. Re-serializing the parsed args list is simpler and produces identical wire format.

## AofRecovery.java

On startup, if recovery is enabled and the AOF file exists:

```java
package dev.redish.aof;

import dev.redish.command.CommandRegistry;
import dev.redish.resp.RespParser;
import dev.redish.store.Store;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;

public class AofRecovery {

    public static void recover(Path aofPath, Store store, CommandRegistry registry) throws IOException {
        try (InputStream in = new FileInputStream(aofPath.toFile())) {
            while (true) {
                Object parsed;
                try {
                    parsed = RespParser.parse(in);
                } catch (Exception e) {
                    break; // truncated or corrupt — stop recovery
                }
                if (parsed == null) break;
                if (parsed instanceof List<?> args && !args.isEmpty()) {
                    String cmdName = ((String) args.get(0)).toUpperCase();
                    registry.get(cmdName).execute((List<Object>) args);
                }
            }
        }
    }
}
```

Uses the existing `RespParser.parse(InputStream)` — the original blocking parser. The InputStream version already handles the protocol.

## AofRewrite.java

Rewrite compacts the AOF by scanning the current Store state and writing a minimal set of commands.

```java
package dev.redish.aof;

import dev.redish.store.Store;
import dev.redish.resp.RespSerializer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class AofRewrite {

    public static void rewrite(Store store, Path aofPath) throws IOException {
        Path tmp = aofPath.resolveSibling(aofPath.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer buf = ByteBuffer.allocate(8192);
            for (var entry : store.allEntries()) {
                // SET key value
                List<Object> setCmd = List.of("SET", entry.key(), entry.data());
                buf = RespSerializer.serialize(setCmd, buf);
                buf.flip();
                ch.write(buf);
                buf.compact();
            }
            // TODO: handle expiry — write PEXPIRE or SET with EX for each expiring key
        }
        java.nio.file.Files.move(tmp, aofPath, StandardOpenOption.ATOMIC_MOVE);
    }
}
```

Requires `Store.allEntries()` to iterate over the key-value map, exposing non-expired entries and their expiry times.

### Rewrite trigger

Check after every write command:

```java
if (aofEnabled && aofWriter.size() > rewriteMinSize &&
    aofWriter.size() > lastRewriteSize * (1 + rewritePercentage / 100.0)) {
    AofRewrite.rewrite(store, aofPath);
    lastRewriteSize = aofWriter.size();
    // truncate and restart AOF
    aofWriter.truncate(0);
}
```

Since the server is single-threaded, the rewrite blocks the event loop. This is acceptable for a learning project — Redis forks a child process for this.

## Changes to Store.java

Add a method to iterate all entries:

```java
public record Entry(String key, byte[] data, long expiresAt) {}

public List<Entry> allEntries() {
    List<Entry> entries = new ArrayList<>();
    for (var e : map.entrySet()) {
        Value v = e.getValue();
        if (!v.isExpired()) {
            entries.add(new Entry(e.getKey(), (byte[]) v.data, v.expiresAt));
        }
    }
    return entries;
}
```

## Integration into Server.java

In the `start()` method:

```java
// Load config
Config config = Config.load("redish.conf", args);
Store store = new Store();

// AOF setup
AofWriter aofWriter = null;
long lastRewriteSize = 0;
boolean aofEnabled = config.isAofEnabled();
Path aofPath = config.getAofPath();

if (aofEnabled) {
    // Recover state from AOF if configured
    if (config.isAofRecoverOnStartup() && Files.exists(aofPath)) {
        AofRecovery.recover(aofPath, store, registry);
    }
    aofWriter = new AofWriter(aofPath, config.getAofFsync());
}
```

In `handleRead`, after executing a write command:

```java
if (aofEnabled && command.isWrite()) {
    ByteBuffer aofCmd = RespSerializer.serialize(cmdArgs, ByteBuffer.allocate(64));
    aofWriter.append(aofCmd);
    // Check rewrite trigger
    checkAofRewrite();
}
```

Add periodic fsync in the event loop (after each select cycle or in handleWrite):

```java
if (aofEnabled) {
    aofWriter.flush(); // flushes buffer to OS
    long now = System.currentTimeMillis();
    if ("always".equals(aofFsync)) {
        aofWriter.fsync();
    } else if ("everysec".equals(aofFsync) && now - lastAofFsync >= 1000) {
        aofWriter.fsync();
        lastAofFsync = now;
    }
}
```

## AOF format

Each command is logged as a RESP array of bulk strings, exactly as it would be sent by a client:

```
*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nvalue\r\n
*2\r\n$3\r\nDEL\r\n$4\r\nkey1\r\n
*3\r\n$6\r\nEXPIRE\r\n$1\r\nk\r\n$2\r\n30\r\n
```

This is what RespSerializer produces from a `List<Object>`.

## Edge cases

| Case | Handling |
|---|---|
| AOF file missing on start | Skip recovery silently |
| Corrupt AOF (truncated) | Stop replay at error, log warning (like Redis `aof-load-truncated`) |
| AOF write failure | Log error, close connection (per Redis: stop accepting writes until fixed) |
| Rewrite during active AOF | Write to `.tmp` file, atomically rename on success |
| Key with expiry in rewrite | Write `SET key value PXAT <absolute-ms>` to preserve remaining TTL |
| Fsync = always | Fsync after every write command (slow) |
| Fsync = no | Never fsync, let OS flush when it wants |
| Fsync = everysec | Fsync at most once per second |

## File changes

| File | Action |
|---|---|
| `redish.conf` | Create — config file with comments |
| `src/main/java/dev/redish/Config.java` | Create — load config from file + CLI flags |
| `src/main/java/dev/redish/aof/AofWriter.java` | Create — buffered AOF writer with fsync policy |
| `src/main/java/dev/redish/aof/AofRecovery.java` | Create — replay AOF on startup |
| `src/main/java/dev/redish/aof/AofRewrite.java` | Create — compact AOF via store scan |
| `src/main/java/dev/redish/store/Store.java` | Add `allEntries()` and `Entry` record |
| `src/main/java/dev/redish/command/Command.java` | Add `default boolean isWrite()` |
| `src/main/java/dev/redish/command/SetCommand.java` | Override `isWrite()` → `true` |
| `src/main/java/dev/redish/command/DelCommand.java` | Override `isWrite()` → `true` |
| `src/main/java/dev/redish/command/ExpireCommand.java` | Override `isWrite()` → `true` |
| `src/main/java/dev/redish/Server.java` | Integration: config load, AOF append, fsync, rewrite trigger |
| `src/test/java/dev/redish/aof/AofWriterTest.java` | Create — write, flush, fsync, truncate |
| `src/test/java/dev/redish/aof/AofRecoveryTest.java` | Create — write AOF, recover into fresh store |
| `src/test/java/dev/redish/aof/AofRewriteTest.java` | Create — write AOF, rewrite, verify compact output |

## Test strategy

| Test | What it verifies |
|---|---|
| `AofWriter` writes RESP bytes to file | Read back bytes match expected RESP |
| `AofWriter` respects fsync policy | File is on disk after fsync |
| `AofRecovery` replays SET/DEL/EXPIRE | Store state matches after replay |
| `AofRecovery` truncated file | Stops at corruption, recovers partial state |
| `AofRewrite` compacts SETs | Multiple SETs of same key → single final value |
| `AofRewrite` preserves expiring keys | Rewritten file still has correct TTL |
| Integration: SET → restart → GET | Value survives restart |



