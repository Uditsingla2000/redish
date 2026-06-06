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

| Command | Behavior |
|---|---|
| `PING` | Returns `PONG` |
| `PING <arg>` | Returns the argument as a bulk string |
| `<anything else>` | Returns error: `ERR unknown command '...'` |

## Project Structure

```
src/
├── main/java/dev/redish/
│   ├── Server.java              # TCP server, RESP dispatch loop
│   ├── Client.java              # Interactive CLI, RESP over PushbackInputStream
│   ├── command/
│   │   ├── Command.java         # Command interface
│   │   ├── CommandRegistry.java # Maps names → handlers
│   │   ├── PingCommand.java     # PING handler
│   │   └── UnknownCommand.java  # Fallback handler
│   └── resp/
│       ├── RespType.java        # RESP type identifiers
│       ├── RespParser.java      # InputStream → Java objects
│       ├── RespSerializer.java  # Java objects → RESP wire format
│       └── ErrorResponse.java   # Error record (auto-serialized)
└── test/java/dev/redish/
    ├── RespParserTest.java      # RESP parsing tests
    ├── RespSerializerTest.java  # RESP serialization tests
    ├── PingCommandTest.java     # PING command tests
    └── CommandRegistryTest.java # Registry tests
```

## Status

Working: RESP protocol, Command pattern, PING, error handling, tests.
Next: Add SET/GET with in-memory store, concurrent clients.
