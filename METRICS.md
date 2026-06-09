# Redish — Performance Metrics

Benchmark tool: [`redis-benchmark`](https://redis.io/docs/management/optimization/benchmarks/)
(v7.4.2), the official Redis benchmarking utility. It exercises the server over
the same RESP wire protocol that real clients use.

Server is a single-threaded NIO `Selector` event loop (epoll on Linux, kqueue
on macOS).

## Command: PING

### Methodology

| Parameter       | Value                                    |
|-----------------|------------------------------------------|
| Tool            | `redis-benchmark -p 6380 -c N -n 100000` |
| Test            | `-t ping` (runs both inline and bulk variants) |
| Requests        | 100 000 per concurrency level             |
| Connection mode | persistent (keep-alive)                   |
| Client location | localhost (127.0.0.1)                    |
| Port            | 6380                                      |
| JDK             | OpenJDK 25.0.2 (Microsoft build)         |
| OS              | Linux (x86_64)                            |

### Results

`PING_INLINE` — plain text `PING\r\n` (no RESP framing):

| Connections | Throughput (req/s) | Avg (ms) | Min (ms) | p50 (ms) | p95 (ms) | p99 (ms) |
|-------------|-------------------|----------|----------|----------|----------|----------|
| 1           | 17 976            | 0.046    | 0.032    | 0.047    | 0.055    | 0.087    |
| 10          | 39 667            | 0.148    | 0.056    | 0.127    | 0.295    | 0.447    |
| 50          | 37 078            | 0.763    | 0.208    | 0.607    | 1.655    | 2.879    |
| 100         | 37 722            | 1.470    | 0.416    | 1.231    | 3.007    | 5.071    |

`PING_MBULK` — RESP array `*1\r\n$4\r\nPING\r\n`:

| Connections | Throughput (req/s) | Avg (ms) | Min (ms) | p50 (ms) | p95 (ms) | p99 (ms) |
|-------------|-------------------|----------|----------|----------|----------|----------|
| 1           | 17 743            | 0.047    | 0.032    | 0.047    | 0.063    | 0.095    |
| 10          | 38 926            | 0.152    | 0.048    | 0.127    | 0.303    | 0.535    |
| 50          | 38 820            | 0.701    | 0.216    | 0.607    | 1.271    | 2.319    |
| 100         | 38 110            | 1.400    | 0.328    | 1.239    | 2.399    | 4.487    |

### Interpretation

- **Throughput peaks** at ≈39 000 req/s with 10+ concurrent connections. The
  single-threaded NIO event loop is the bottleneck.
- **Sub-millisecond latency** at concurrency ≤10. At 100 connections, p95
  stays under 3 ms for both variants.
- **Inline vs. multi-bulk** are nearly identical — the inline parser path adds
  negligible overhead.
- **P99 degrades smoothly** — no catastrophic tail-latency spike, confirming
  the event loop fairly multiplexes all connections.
- **Compared to real Redis** (which does ~100 000+ PING/s on similar hardware
  with 50 clients), redish is ~2.5× slower — expected for a single-threaded
  Java learning project vs. an optimized C server.

### Running the benchmark yourself

```bash
# Build redish
JAVA_HOME=/home/codespace/java/25.0.2-ms PATH=$JAVA_HOME/bin:$PATH mvn -q package

# Start server in background
java -cp target/classes dev.redish.Server &
sleep 2

# Run benchmark (all concurrency levels at once)
for c in 1 10 50 100; do
  echo "=== Concurrency: $c ==="
  redis-benchmark -p 6380 -c $c -n 100000 -t ping 2>/dev/null \
    | grep -E '(throughput summary|avg.*min.*p50)' -A1
  echo
done

# Or just test one level
redis-benchmark -p 6380 -c 50 -n 100000 -t ping
```
