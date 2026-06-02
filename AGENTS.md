# redish — AGENTS.md

## Project intent

Redis clone built for learning low-level design patterns in Java. Simplicity and clarity over production polish.

## Quick start

```bash
mvn compile                                # build
mvn exec:java -Dexec.mainClass=dev.redish.Server   # start server (port 6380)
mvn exec:java -Dexec.mainClass=dev.redish.Client   # start interactive client
```

No Maven wrapper — requires `mvn` on `$PATH`. Java 24.

## Architecture

| Package | What |
|---|---|
| `dev.redish` | Entrypoints — `Server` (TCP listener) and `Client` (interactive test tool) |
| `dev.redish.resp` | RESP protocol — `RespType`, `RespParser`, `RespSerializer` |

**Current state (Phase 1):** Server echoes raw text lines — does NOT speak RESP yet. The `resp` package is usable but not wired into Server/Client. Wired RESP support is the next step.

## Port

`6380` — deliberately avoids conflicting with a real Redis on 6379.

## Testing

No test framework configured yet (no JUnit dep, no `src/test`). Add one when tests are written.

## Patterns & design

This is a learning project. When adding features, prefer explicit, readable patterns over framework magic. Every phase should demonstrate one or two concrete design patterns (e.g., Command, Strategy, Observer, Reactor, etc.).

## RESP protocol layer

- Parser reads from `InputStream` → returns `String`, `Long`, `List<Object>`, or `null`
- Serializer writes Java objects → RESP wire format
- Simple strings auto-upgrade to bulk strings when content contains CR/LF
- Null bulk string (`$-1\r\n`) and null array (`*-1\r\n`) supported
- Not binary-safe (bulk strings decoded as UTF-8)
