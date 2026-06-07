# impl.md — I/O multiplexing (single-threaded concurrency)

## Why

Current `Server.java` accepts one client, handles it, then loops back to `accept()`. If that client never sends data, nobody else connects. We need many clients in one thread.

## What is I/O multiplexing

One thread monitors many file descriptors (sockets) and only works on the ones that are "ready" — readable (data arrived), writable (buffer has room), or acceptable (new connection).

On your machine (macOS) this is **kqueue**. On Linux it's **epoll**. Java's `java.nio.channels.Selector` wraps whichever the OS provides — same API.

```
// Without multiplexing (current):
accept() → blocks until one client connects
read()   → blocks until that client sends data
write()  → blocks until buffer drains

// With multiplexing:
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
| `ByteBuffer` | Buffer for reading/writing bytes (off-heap or heap) |

## The event loop (pseudocode)

```java
Selector selector = Selector.open();
ServerSocketChannel ssc = ServerSocketChannel.open();
ssc.bind(new InetSocketAddress(port));
ssc.configureBlocking(false);
ssc.register(selector, SelectionKey.OP_ACCEPT);

while (true) {
    selector.select();               // blocks until at least one channel is ready
    Set<SelectionKey> keys = selector.selectedKeys();
    Iterator<SelectionKey> it = keys.iterator();
    while (it.hasNext()) {
        SelectionKey key = it.next();
        it.remove();                 // must remove after processing

        if (key.isAcceptable())  handleAccept(key);   // new connection
        if (key.isReadable())    handleRead(key);     // data arrived
        if (key.isWritable())    handleWrite(key);    // buffer has room
    }
}
```

## Channel states

Each connected `SocketChannel` moves through states:

```
[ACCEPT] → attach read buffer, register for OP_READ
             ↓
[READ]   → read into ByteBuffer
             ↓  (need more data)
          keep reading (maybe partial RESP)
             ↓  (full RESP frame received)
          parse → execute command → serialize → write into write buffer
             ↓
[WRITE]  → register for OP_WRITE, write pending data
             ↓  (write buffer drained)
          register for OP_READ (back to reading next command)
             ↓
[CLOSE]  → channel closed by client or error → cancel key, close channel
```

## State per connection

Each `SelectionKey` needs an attachment to hold per-connection state:

```java
class ConnectionState {
    ByteBuffer readBuf;    // accumulates incoming bytes
    ByteBuffer writeBuf;   // pending bytes to send
    // could add: partial parse state (e.g., RESP frame in progress)
}
```

`SelectionKey.attach(state)` → retrieve with `key.attachment()`.

## Interaction with existing code

The tricky part: current `RespParser.parse(InputStream)` and `RespSerializer.serialize(Object, OutputStream)` work with blocking streams. NIO uses buffers.

**Two approaches:**

### Option A: Bridge with Channels

Wrap `SocketChannel` in an `InputStream`/`OutputStream`:
- `Channels.newInputStream(socketChannel)` — but this blocks if no data available (defeats purpose)
- Actually InputStream wrapper on non-blocking channel can return 0 / throw if no data

This doesn't work cleanly because NIO is fundamentally non-blocking.

### Option B: Rewrite protocol layer for ByteBuffer (recommended)

Add non-blocking variants of parser/serializer that work with `ByteBuffer` directly:

```java
// Current (blocking, InputStream based):
public static Object parse(InputStream in) throws IOException

// New (non-blocking, ByteBuffer based):
public static Object parse(ByteBuffer buf)
// Returns null if incomplete frame (need more data)
// Returns parsed object if complete frame
```

Similarly for serializer:
```java
// Current:
public static void serialize(Object obj, OutputStream out)

// New — appends to ByteBuffer:
public static void serialize(Object obj, ByteBuffer buf)
```

The event loop then:
1. **read**: `socketChannel.read(readBuf)` → flip → `RespParser.parse(readBuf)` → if null (partial), compact and wait for more data; if complete, execute command → serialize into writeBuf
2. **write**: `socketChannel.write(writeBuf)` → if remaining, keep OP_WRITE; if drained, switch to OP_READ

This is how Redis itself works — it parses from an input buffer incrementally.

## File changes needed

### New file: `ConnectionState.java`

```java
class ConnectionState {
    ByteBuffer readBuf = ByteBuffer.allocate(8192);
    ByteBuffer writeBuf = ByteBuffer.allocate(8192);
}
```

### Modified: `RespParser.java`

Add overload:
```java
public static Object parse(ByteBuffer buf) { ... }
```
Returns `null` if complete RESP frame not yet available. Otherwise same logic but reads from buffer instead of InputStream.

### Modified: `RespSerializer.java`

Add overload:
```java
public static void serialize(Object obj, ByteBuffer buf) { ... }
```

### Modified: `RespType.java`

Maybe no changes needed.

### Rewrite: `Server.java`

Replace blocking `ServerSocket` + loop with NIO event loop.

### Unchanged: `Client.java`, `Command.java`, commands, tests

The command layer (`execute(List<Object> args)`) is pure logic — no I/O. Tests stay the same.

## Edge cases

| Case | Handling |
|---|---|
| Partial RESP frame (client sent half) | Parser returns null → stay in OP_READ, accumulate more bytes |
| Client disconnects | `read()` returns -1 → cancel key, close channel |
| Write buffer full | Stay in OP_WRITE mode, drain in chunks |
| Multiple commands in one read (pipelining) | Loop `parse()` until null, then switch to OP_WRITE with all responses |
| Client sends garbage | Parser throws → create error response, write back, close |
| Large bulk strings (> 8KB buffer) | Grow buffer or use `read()` with remaining capacity |

## Testing strategy

- Existing command unit tests unchanged
- New: integration test with multiple `SocketChannel` clients connecting simultaneously, sending pipelined commands, verifying correct responses
- Concurrency test: 10 clients connect, each sends 100 PINGs, verify all get correct responses (single-threaded but interleaved)

## What you implement

I suggest tackling in 5 steps:

1. **Read/parse**: Add `ByteBuffer`-based `RespParser.parse(ByteBuffer)` returning null on partial frames
2. **Serialize**: Add `ByteBuffer`-based `RespSerializer.serialize(Object, ByteBuffer)`
3. **ConnectionState**: Per-key attachment
4. **Event loop**: Replace Server.java with NIO selector loop
5. **Test**: Manual test with `rcli` + `telnet` + second `rcli`

Want me to review each step as you go? Or do the whole thing and then review at the end?
