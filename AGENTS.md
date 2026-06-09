# redish — AGENTS.md

## Project intent

Redis clone built for learning low-level design patterns in Java. Simplicity and clarity over production polish.

## Quick start

```bash
./rserver        # start server on port 6380
./rcli           # start interactive client

# Or without scripts:
mvn compile
mvn exec:java -Dexec.mainClass=dev.redish.Server
mvn exec:java -Dexec.mainClass=dev.redish.Client

mvn test         # run all tests (54 total)
```

No Maven wrapper — requires `mvn` on `$PATH`. Java 24.

## Port

`6380` — deliberately avoids conflicting with a real Redis on 6379.

## Architecture

| Package | What |
|---|---|
| `dev.redish` | Entrypoints — `Server` (NIO event loop), `Client` (interactive CLI), `ConnectionState` |
| `dev.redish.command` | Command pattern — `Command` interface, `PingCommand`, `CommandRegistry`, `UnknownCommand` |
| `dev.redish.resp` | RESP protocol — `RespType`, `RespParser`, `RespSerializer`, `ErrorResponse`, `RespException` |

## Design patterns used

- **Command Pattern** — `Command` interface, each command is a class. Registry maps name → handler.
- **ErrorResponse record** — errors returned as `ErrorResponse` record, auto-serialized by `RespSerializer`.
- **I/O Multiplexing** — single-threaded NIO `Selector` event loop (kqueue on macOS, epoll on Linux).
- **ByteBuffer Parser** — idempotent rewind-on-null pattern for non-blocking parsing.

## RESP protocol layer

- `RespParser.parse(InputStream)` — blocking, used by `Client.java`
- `RespParser.parse(ByteBuffer)` — non-blocking, used by `Server.java`, returns null on partial frames
- `RespSerializer.serialize(Object, OutputStream)` — blocking, used by `Client.java`
- `RespSerializer.serialize(Object, ByteBuffer)` — non-blocking, returns (possibly reallocated) buffer, used by `Server.java`
- `grow()` — dynamically doubles buffer capacity, no size limit on responses
- Simple strings auto-upgrade to bulk strings when content contains CR/LF
- Null bulk string (`$-1\r\n`) and null array (`*-1\r\n`) supported
- `RespException` for protocol errors (bad type byte, malformed data)

## Testing

JUnit 5 + Surefire. Run all: `mvn test`
Current count: **54 tests** — 13 InputStream parser, 32 ByteBuffer parser+serializer, 1 OutputStream serializer, 4 PingCommand, 4 CommandRegistry

## Current state

- **Server**: NIO `Selector` event loop — single-threaded, handles many concurrent clients
- **Client**: blocking RESP CLI, interactive loop
- **PING** → `+PONG`, `PING <arg>` → bulk string, `PING` with >1 arg → error
- Unknown commands → `-ERR unknown command '...'`
- Dynamic buffer growth — no limit on response size
- Accept queue drained in loop — handles burst connections
