# Redish — A Redis Clone in Java

A step-by-step reimplementation of Redis internals in Java 24, following along with Arpit Bhayani's Redis internals playlist.

## Project Structure

This is a Maven project structured around building both the Redis server and a custom Redis client (CLI).

## Prerequisites

- **Java 24** (or higher)
- **Maven** (3.9+)

## Setup and Running

1. **Clone the repository and navigate to the project directory:**
   ```bash
   cd redish
   ```

2. **Compile the project:**
   ```bash
   mvn clean compile
   ```

3. **Run the Server:**
   In one terminal tab, start the Redish server:
   ```bash
   mvn exec:java -Dexec.mainClass=dev.redish.Server
   ```
   *By default, the server binds to port 6380 (to avoid conflicting with a real Redis instance on 6379).*

4. **Run the Client (Interactive CLI):**
   In a separate terminal tab, connect to the server using our built-in client:
   ```bash
   mvn exec:java -Dexec.mainClass=dev.redish.Client
   ```

## Current Features
- **Phase 1:** Basic TCP Client-Server model (Single-threaded blocking I/O)
- **Phase 2 (In Progress):** Custom RESP (Redis Serialization Protocol) Parser & Serializer.
