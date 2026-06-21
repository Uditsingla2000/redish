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

mvn test         # run all tests (110 total)
```

No Maven wrapper — requires `mvn` on `$PATH`. Java 24.

## Port

`6380` — deliberately avoids conflicting with a real Redis on 6379.

## Architecture

| Package | What |
|---|---|
| `dev.redish` | Entrypoints — `Server` (NIO event loop), `Client` (interactive CLI), `ConnectionState`, `Log` (file logger) |
| `dev.redish.command` | Command pattern — `Command` interface, 8 commands, `CommandRegistry`, `UnknownCommand` |
| `dev.redish.store` | In-memory key-value store with lazy expiry |
| `dev.redish.resp` | RESP protocol — `RespType`, `RespParser`, `RespSerializer`, `ErrorResponse`, `RespException` |

## Design patterns used

- **Command Pattern** — `Command` interface, each command is a class. Registry maps name → handler.
- **ErrorResponse record** — errors returned as `ErrorResponse` record, auto-serialized by `RespSerializer`.
- **I/O Multiplexing** — single-threaded NIO `Selector` event loop (kqueue on macOS, epoll on Linux).
- **ByteBuffer Parser** — idempotent rewind-on-null pattern for non-blocking parsing.
- **Command Pipelining** — drain loop in `handleRead` processes up to 5000 commands per read event.
- **Lazy Expiry** — expired keys purged on access; no background thread.

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
Current count: **110 tests**
- 13 InputStream parser, 32 ByteBuffer parser+serializer, 1 OutputStream serializer
- 4 PingCommand, 4 CommandRegistry
- 6 SetCommand, 4 GetCommand, 7 TtlCommand
- 6 DelCommand, 7 ExpireCommand
- 15 Store
- 11 Pipeline

## Implemented commands

| Command | Args | Returns |
|---|---|---|
| `PING` | `[arg]` | `+PONG` or bulk string |
| `SET` | `key value [EX seconds]` | `+OK` |
| `GET` | `key` | bulk string or `$-1` |
| `TTL` | `key` | `:<seconds>` or `:-1` |
| `DEL` | `key [key ...]` | `:<count>` |
| `EXPIRE` | `key seconds` | `:1` or `:0` |
| Unknown | any | `-ERR unknown command '...'` |

## Current state

- **Server**: NIO `Selector` event loop — single-threaded, handles many concurrent clients
- **Pipeline**: drain loop processes all complete commands in one read event (cap 5000)
- **Store**: in-memory `HashMap` with lazy expiry, shared across commands
- **Client**: blocking RESP CLI, interactive loop
- **Dynamic buffer growth** — no limit on response size
- **Accept queue drained in loop** — handles burst connections
- **File logging** — `logs/YYYY-MM-DD.log` via `Log.info()`
