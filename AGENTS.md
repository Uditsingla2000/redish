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

mvn test         # run all tests
```

No Maven wrapper — requires `mvn` on `$PATH`. Java 24.

## Port

`6380` — deliberately avoids conflicting with a real Redis on 6379.

## Architecture

| Package | What |
|---|---|
| `dev.redish` | Entrypoints — `Server` (TCP listener), `Client` (interactive CLI) |
| `dev.redish.command` | Command pattern — `Command` interface, `PingCommand`, `CommandRegistry`, `UnknownCommand` |
| `dev.redish.resp` | RESP protocol — `RespType`, `RespParser`, `RespSerializer`, `ErrorResponse` |

## Design patterns used

- **Command Pattern** — `Command` interface, each command is a class. Registry maps name → handler.
- **ErrorResponse record** — errors returned as `ErrorResponse` record, auto-serialized by `RespSerializer` as `-ERR...\r\n`. No public writeError needed.

## RESP protocol layer

- `RespParser.parse(InputStream)` → returns `String`, `Long`, `List<Object>`, or `null`
- `RespSerializer.serialize(Object, OutputStream)` — writes RESP wire format
- `RespSerializer.serialize(new ErrorResponse("ERR ..."), out)` — writes `-ERR ...\r\n`
- Simple strings auto-upgrade to bulk strings when content contains CR/LF
- Null bulk string (`$-1\r\n`) and null array (`*-1\r\n`) supported
- Not binary-safe (bulk strings decoded as UTF-8)

## Testing

JUnit 5 + Surefire. Tests at `src/test/java/`.
Run all: `mvn test`
Current count: 22 tests (RespParser, RespSerializer, PingCommand, CommandRegistry)

## Current state

- Server reads RESP from client, dispatches via CommandRegistry, writes RESP response
- Client sends tokenized commands as RESP arrays, displays responses
- PING → `+PONG`, PING \<arg\> → `$<len>\r\n<arg>\r\n`, PING with >1 arg → error
- Unknown commands → `-ERR unknown command '...'`
- Server handles one client at a time (sequential, blocking I/O)
