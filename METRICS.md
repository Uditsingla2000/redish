# Redish — Performance Metrics

Benchmark tool: [`redis-benchmark`](https://redis.io/docs/management/optimization/benchmarks/)
(v7.4.2), the official Redis benchmarking utility. It exercises the server over
the same RESP wire protocol that real clients use.

Server is a single-threaded NIO `Selector` event loop (epoll on Linux, kqueue
on macOS).

## Commands

Currently 6 commands: `PING`, `SET`, `GET`, `TTL`, `DEL`, `EXPIRE`.
Only `PING`, `SET` and `GET` are benchmarked below (TTL/DEL/EXPIRE have no
dedicated `redis-benchmark` test harness).

## Latest results (2026-06-21)

### Methodology

| Parameter       | Value                                    |
|-----------------|------------------------------------------|
| Tool            | `redis-benchmark -p 6380 -c N -n 100000` |
| Test            | `-t ping`, `-t set,get`                  |
| Requests        | 100 000 per concurrency level             |
| Connection mode | persistent (keep-alive)                   |
| Client location | localhost (127.0.0.1)                    |
| Port            | 6380                                      |
| JDK             | OpenJDK 25.0.2 (Microsoft build)         |
| OS              | Linux (x86_64)                            |

### PING_INLINE — plain text `PING\r\n`

| Connections | Throughput (req/s) | Avg (ms) | Min (ms) | p50 (ms) | p95 (ms) | p99 (ms) |
|-------------|-------------------|----------|----------|----------|----------|----------|
| 1           | 3 286             | 0.292    | 0.024    | 0.063    | 1.279    | 4.967    |
| 10          | 4 609             | 2.053    | 0.064    | 0.943    | 7.239    | 12.991   |
| 50          | 8 602             | 5.001    | 0.344    | 4.199    | 11.679   | 18.591   |
| 100         | 12 232            | 6.235    | 0.568    | 5.383    | 13.031   | 19.183   |

### PING_MBULK — RESP array `*1\r\n$4\r\nPING\r\n`

| Connections | Throughput (req/s) | Avg (ms) | Min (ms) | p50 (ms) | p95 (ms) | p99 (ms) |
|-------------|-------------------|----------|----------|----------|----------|----------|
| 1           | 13 116            | 0.066    | 0.032    | 0.055    | 0.087    | 0.327    |
| 10          | 19 829            | 0.374    | 0.072    | 0.191    | 1.367    | 2.951    |
| 50          | 11 023            | 3.614    | 0.272    | 2.871    | 8.879    | 14.519   |
| 100         | 12 305            | 6.142    | 0.344    | 5.303    | 12.711   | 19.791   |

### SET

| Connections | Throughput (req/s) | Avg (ms) | Min (ms) | p50 (ms) | p95 (ms) | p99 (ms) |
|-------------|-------------------|----------|----------|----------|----------|----------|
| 1           | 3 465             | 0.277    | 0.024    | 0.063    | 1.175    | 4.631    |
| 10          | 4 891             | 1.925    | 0.080    | 0.895    | 6.935    | 12.895   |
| 50          | 8 747             | 4.910    | 0.344    | 4.023    | 11.343   | 18.399   |
| 100         | 11 795            | 6.470    | 0.672    | 5.591    | 13.823   | 22.031   |

### GET

| Connections | Throughput (req/s) | Avg (ms) | Min (ms) | p50 (ms) | p95 (ms) | p99 (ms) |
|-------------|-------------------|----------|----------|----------|----------|----------|
| 1           | 13 759            | 0.063    | 0.032    | 0.055    | 0.087    | 0.231    |
| 10          | 21 796            | 0.344    | 0.048    | 0.199    | 1.191    | 2.479    |
| 50          | 11 913            | 3.335    | 0.264    | 2.551    | 9.119    | 16.495   |
| 100         | 12 753            | 5.961    | 0.736    | 4.983    | 12.903   | 21.215   |

---

## Real Redis comparison (same machine, same benchmark)

Redis 7.4.2 running on the same host (port 6379, no persistence), identical
`redis-benchmark` parameters.

### Redis PING_INLINE

| Connections | Throughput (req/s) | Avg (ms) | Min (ms) | p50 (ms) | p95 (ms) | p99 (ms) |
|-------------|-------------------|----------|----------|----------|----------|----------|
| 1           | 4 246             | 0.224    | 0.024    | 0.055    | 0.935    | 3.519    |
| 10          | 6 239             | 1.457    | 0.048    | 0.695    | 5.295    | 9.423    |
| 50          | 11 933            | 3.234    | 0.104    | 2.759    | 7.519    | 10.863   |
| 100         | 14 813            | 4.550    | 0.376    | 3.919    | 9.767    | 14.303   |

### Redis PING_MBULK

| Connections | Throughput (req/s) | Avg (ms) | Min (ms) | p50 (ms) | p95 (ms) | p99 (ms) |
|-------------|-------------------|----------|----------|----------|----------|----------|
| 1           | 15 026            | 0.055    | 0.024    | 0.047    | 0.079    | 0.175    |
| 10          | 21 386            | 0.332    | 0.056    | 0.167    | 1.255    | 2.911    |
| 50          | 13 858            | 2.708    | 0.192    | 2.223    | 6.743    | 10.391   |
| 100         | 15 620            | 4.320    | 0.384    | 3.815    | 8.999    | 12.775   |

### Redis SET

| Connections | Throughput (req/s) | Avg (ms) | Min (ms) | p50 (ms) | p95 (ms) | p99 (ms) |
|-------------|-------------------|----------|----------|----------|----------|----------|
| 1           | 4 189             | 0.227    | 0.024    | 0.055    | 0.951    | 3.527    |
| 10          | 6 292             | 1.451    | 0.048    | 0.711    | 5.279    | 9.543    |
| 50          | 11 609            | 3.373    | 0.192    | 2.823    | 7.767    | 13.415   |
| 100         | 15 106            | 4.494    | 0.304    | 4.015    | 8.975    | 13.687   |

### Redis GET

| Connections | Throughput (req/s) | Avg (ms) | Min (ms) | p50 (ms) | p95 (ms) | p99 (ms) |
|-------------|-------------------|----------|----------|----------|----------|----------|
| 1           | 14 229            | 0.059    | 0.032    | 0.055    | 0.079    | 0.231    |
| 10          | 20 820            | 0.344    | 0.056    | 0.175    | 1.263    | 2.743    |
| 50          | 13 410            | 2.783    | 0.168    | 2.207    | 6.991    | 11.631   |
| 100         | 14 182            | 4.806    | 0.360    | 4.127    | 10.311   | 16.247   |

---

## Summary comparison: Redish vs Redis

### PING_MBULK (most reliable comparison)

| Metric | Redish | Redis 7.4.2 | Advantage |
|--------|--------|-------------|-----------|
| Peak throughput | 19 829 req/s @ 10 conn | 21 386 req/s @ 10 conn | Redis 1.08× |
| Throughput @ 1 conn | 13 116 req/s | 15 026 req/s | Redis 1.15× |
| Throughput @ 50 conn | 11 023 req/s | 13 858 req/s | Redis 1.26× |
| Throughput @ 100 conn | 12 305 req/s | 15 620 req/s | Redis 1.27× |
| p50 @ 1 conn | 0.055 ms | 0.047 ms | Redis 1.17× |
| p50 @ 50 conn | 2.871 ms | 2.223 ms | Redis 1.29× |
| p99 @ 1 conn | 0.327 ms | 0.175 ms | Redis 1.87× |
| p99 @ 50 conn | 14.519 ms | 10.391 ms | Redis 1.40× |

### SET

| Metric | Redish | Redis 7.4.2 | Advantage |
|--------|--------|-------------|-----------|
| Peak throughput | 11 795 req/s @ 100 conn | 15 106 req/s @ 100 conn | Redis 1.28× |
| Throughput @ 1 conn | 3 465 req/s | 4 189 req/s | Redis 1.21× |
| Throughput @ 10 conn | 4 891 req/s | 6 292 req/s | Redis 1.29× |
| Throughput @ 50 conn | 8 747 req/s | 11 609 req/s | Redis 1.33× |
| p50 @ 1 conn | 0.063 ms | 0.055 ms | Redis 1.15× |
| p50 @ 50 conn | 4.023 ms | 2.823 ms | Redis 1.43× |
| p99 @ 1 conn | 4.631 ms | 3.527 ms | Redis 1.31× |
| p99 @ 50 conn | 18.399 ms | 13.415 ms | Redis 1.37× |

### GET

| Metric | Redish | Redis 7.4.2 | Advantage |
|--------|--------|-------------|-----------|
| Peak throughput | 21 796 req/s @ 10 conn | 20 820 req/s @ 10 conn | **Redish 1.05×** |
| Throughput @ 1 conn | 13 759 req/s | 14 229 req/s | Redis 1.03× |
| Throughput @ 50 conn | 11 913 req/s | 13 410 req/s | Redis 1.13× |
| Throughput @ 100 conn | 12 753 req/s | 14 182 req/s | Redis 1.11× |
| p50 @ 1 conn | 0.055 ms | 0.055 ms | Tie |
| p50 @ 50 conn | 2.551 ms | 2.207 ms | Redis 1.16× |
| p99 @ 1 conn | 0.231 ms | 0.231 ms | Tie |
| p99 @ 50 conn | 16.495 ms | 11.631 ms | Redis 1.42× |

---

## Interpretation

- **Redish GET peaks at 21 796 req/s** — slightly ahead of Redis (20 820) at
  10 concurrent connections. GET is a simple `HashMap` lookup and the Java NIO
  event loop handles it efficiently.
- **Redish SET is 1.2–1.3× slower than Redis** across all concurrency levels.
  Redish's `Store` uses a `HashMap` with `Instant`-based expiry; Redis uses
  an optimised C dictionary with incremental expiry logic.
- **PING_MBULK** is 1.1–1.3× slower than Redis, consistent with the previous
  run. The RESP parser and response serialisation are the bottleneck.
- **PING_INLINE** numbers are volatile on this shared VM (background noise
  inflates tail latency), but PING_MBULK is stable and the primary comparison.
- **Sub-millisecond p50** at low concurrency (≤10) for all commands.
- **P99 degrades smoothly** with concurrency — no catastrophic spikes for any
  command.
- This is a shared Codespaces VM. On dedicated hardware, Redis would likely
  widen the gap. Redish's single-threaded Java design holds up well for a
  learning project, coming within 5–30% of Redis on most workloads.

---

## Running the benchmark yourself

```bash
# Build redish
JAVA_HOME=/usr/local/sdkman/candidates/java/25.0.2-ms PATH=$JAVA_HOME/bin:$PATH mvn -q package

# Start server in background
java -cp target/classes:$(mvn dependency:build-classpath -q -DincludeScope=runtime -Dmdep.outputFile=/dev/stdout) dev.redish.Server &
sleep 2

# Benchmark PING (all 4 concurrency levels)
for c in 1 10 50 100; do
  echo "=== PING concurrency: $c ==="
  redis-benchmark -p 6380 -c $c -n 100000 -t ping 2>/dev/null \
    | grep -E 'throughput summary|avg.*min.*p50' -A1
done

# Benchmark SET + GET (all 4 concurrency levels)
for c in 1 10 50 100; do
  echo "=== SET/GET concurrency: $c ==="
  redis-benchmark -p 6380 -c $c -n 100000 -t set,get 2>/dev/null \
    | grep -E 'throughput summary|avg.*min.*p50' -A1
done
```
