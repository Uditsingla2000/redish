# Redish — A Redis Clone in Java

A step-by-step reimplementation of Redis internals in Java 24, following along with Arpit Bhayani's Redis internals playlist. Focus on low-level design patterns.

## Prerequisites

- **Java 24** (or higher)
- **Maven** (3.9+)

## Setup and Running

```bash
git clone <repo>
cd redish

# Build
mvn compile

# Start server (port 6380)
mvn exec:java -Dexec.mainClass=dev.redish.Server
# or: ./rserver

# Start server with AOF persistence
mvn exec:java -Dexec.mainClass=dev.redish.Server -Dexec.args="--aof"
# or: ./rserver --aof

# Start interactive client (separate terminal)
mvn exec:java -Dexec.mainClass=dev.redish.Client
# or: ./rcli

# Run tests
mvn test
```


## Shell Scripts

- `rserver` — shortcut to run the server
- `rcli` — shortcut to run the client


## Implemented Commands

| Command | Args | Behavior | Returns |
|---|---|---|---|
| `PING` | `[arg]` | Echo or return PONG | `+PONG` or bulk string |
| `SET` | `key value [EX seconds]` | Store key with optional expiry | `+OK` |
| `GET` | `key` | Retrieve value or nil | bulk string or `$-1` |
| `TTL` | `key` | Seconds remaining or -1 if no expiry | `:<seconds>` or `:-1` |
| `DEL` | `key [key ...]` | Delete keys, returns count | `:<count>` |
| `EXPIRE` | `key seconds` | Set expiry in seconds | `:1` or `:0` |

## Features

- **Pipelining** — multiple commands in one write, processed in a single drain loop (cap 5000)
- **Lazy expiry** — expired keys purged on access, no background thread
- **AOF persistence** — Append-Only File logging (`--aof` flag), fsync policies (always/everysec/no), recovery on startup, rewrite compaction

## Project Structure

```
src/
├── main/java/dev/redish/
│   ├── Server.java              # NIO Selector event loop
│   ├── Client.java              # Interactive CLI, RESP over PushbackInputStream
│   ├── ConnectionState.java     # Per-connection read/write buffers
│   ├── Log.java                 # File logger to logs/YYYY-MM-DD.log
│   ├── Logo.java                # ASCII art logo
│   ├── Config.java              # Config file + CLI flag parser
│   ├── command/
│   │   ├── Command.java         # Interface (isWrite())
│   │   ├── CommandRegistry.java # Maps names → handlers
│   │   ├── PingCommand.java
│   │   ├── SetCommand.java      # isWrite() = true
│   │   ├── GetCommand.java
│   │   ├── TtlCommand.java
│   │   ├── DelCommand.java      # isWrite() = true
│   │   ├── ExpireCommand.java   # isWrite() = true
│   │   └── UnknownCommand.java  # Fallback
│   ├── store/
│   │   └── Store.java           # HashMap-backed key-value store with lazy expiry, allEntries()
│   ├── aof/
│   │   ├── AofWriter.java       # Buffered FileChannel writer
│   │   ├── AofRecovery.java     # Replay AOF on startup
│   │   └── AofRewrite.java      # Compact rewrite
│   └── resp/
│       ├── RespType.java
│       ├── RespParser.java      # InputStream + ByteBuffer parsers
│       ├── RespSerializer.java  # OutputStream + ByteBuffer serializers
│       ├── ErrorResponse.java
│       └── RespException.java
└── test/java/dev/redish/
    ├── PipelineTest.java        # Pipelining tests (11)
    ├── aof/
    │   ├── AofWriterTest.java   # AOF writer tests (4)
    │   ├── AofRecoveryTest.java # AOF recovery tests (4)
    │   └── AofRewriteTest.java  # AOF rewrite tests (4)
    ├── command/
    │   ├── PingCommandTest.java
    │   ├── SetCommandTest.java
    │   ├── GetCommandTest.java
    │   ├── TtlCommandTest.java
    │   ├── DelCommandTest.java
    │   ├── ExpireCommandTest.java
    │   └── CommandRegistryTest.java
    ├── store/
    │   └── StoreTest.java
    └── resp/
        ├── RespParserTest.java
        ├── RespSerializerTest.java
        └── RespByteBufferTest.java
```

## Status

Working: RESP protocol, Command pattern, PING, SET, GET, TTL, DEL, EXPIRE, pipelining, in-memory store with lazy expiry, NIO event loop, file logging, AOF persistence (write, recovery, rewrite, fsync policies).

**122 tests** — all passing (`mvn test`).
