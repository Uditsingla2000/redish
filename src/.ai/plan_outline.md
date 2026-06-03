# Redish — Project Roadmap & Plan Outline

This document tracks our high-level roadmap as we build a Redis clone from scratch, with a strong emphasis on building a functional server *and* a robust custom CLI client.

## Phase 1: The Foundation (Completed)
- [x] Basic Maven project setup (Java 24).
- [x] Single-threaded blocking TCP Echo Server (`ServerSocket` / `Socket`).
- [x] Basic TCP Client that can send/receive text over sockets.

## Phase 2: RESP Protocol (In Progress)
Redis clients and servers communicate using the **REdis Serialization Protocol (RESP)**. 
- [x] Implement a robust parser (`RespParser`) to decode incoming RESP byte streams into Java objects (Simple Strings, Bulk Strings, Integers, Arrays, Errors).
- [x] Implement a serializer (`RespSerializer`) to convert Java objects back into the RESP wire format.
- [ ] Add unit tests for the RESP parsing and serialization logic.

## Phase 3: The Command Parser & Data Store
Before we handle concurrent clients, we need the server to actually *do* something other than echo.
- [ ] Create a central `DataStore` (a wrapper around `ConcurrentHashMap` or a standard `HashMap` if remaining single-threaded for now).
- [ ] Parse incoming RESP Arrays into logical Commands (e.g., `["SET", "key", "value"]`).
- [ ] Implement command execution for core commands: `PING`, `ECHO`, `SET`, `GET`.
- [ ] Return appropriate RESP responses (e.g., Simple String `+OK\r\n` for `SET`, Bulk String for `GET`).

## Phase 4: Building the Custom Redis CLI
Since we want to build a fully-fledged CLI to pair with the server, we need to upgrade `Client.java`.
- [ ] Create a REPL (Read-Eval-Print Loop) environment.
- [ ] Parse user input (e.g., `SET name udit`) and translate it into a RESP Array (`*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$4\r\nudit\r\n`) to send to the server.
- [ ] Receive raw RESP data from the server and render it in a human-readable, formatted way (just like the real `redis-cli`). 
    - E.g., bulk strings rendered as `"value"`, arrays as numbered lists, errors as `(error) ERR...`.
- [ ] Support connection management and graceful shutdowns.

## Phase 5: Concurrency & Event Loops
A real Redis server handles thousands of concurrent connections efficiently. 
- [ ] Transition from Blocking I/O (`java.io`) to Non-Blocking I/O (`java.nio`).
- [ ] Implement an Event Loop / Reactor pattern using `Selector`.
- [ ] Handle partial reads/writes for RESP frames asynchronously without blocking the main thread.

## Phase 6: Expiry (TTL) & Background Tasks
- [ ] Support `SET key value EX seconds` and `EXPIRE key seconds`.
- [ ] Implement lazy eviction (checking expiry on `GET`).
- [ ] Implement active eviction (background thread randomly sampling keys to expire them).

## Phase 7: Persistence (Optional/Stretch)
- [ ] **AOF (Append Only File):** Log every mutation command to a file and replay it on startup.
- [ ] **RDB (Snapshotting):** Serialize the entire HashMap state to disk periodically using background threads (or `fork()` concepts in Java via separate processes if possible, though Java makes this tricky).
