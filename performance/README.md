# Performance Testing

## Prerequisites

| Tool | Install | Used For |
|------|---------|----------|
| **curl** | Pre-installed on Windows 10+ | Quick single-request latency |
| **ab** | Install [Apache Lounge](https://www.apachelounge.com/download/) or XAMPP | Burst testing |
| **k6** | `npm install -g k6` or [k6.io](https://k6.io/docs/get-started/installation/) | Load/spike testing |

## Quick Latency Test (curl)

Tests one request per endpoint with detailed timing breakdown.

```cmd
cd performance
quick-latency.bat
REM or with custom URL:
quick-latency.bat http://localhost:8081
```

**Output example:**
```
>> POST /api/auth/login
  DNS:        0.001s
  Connect:    0.002s
  TLS:        0.000s
  TTFB:       0.045s
  Total:      0.047s
  HTTP Code:  200
```

## Burst Test (Apache Bench)

Sends N requests at C concurrency, reports latency percentiles.

```cmd
cd performance
burst-test.bat
REM or with custom settings:
burst-test.bat http://localhost:8081 1000 50
```

**Key metrics to watch:**
- `Time per request` — mean latency
- `50%/95%/99%` — percentile latencies
- `Failed requests` — should be 0

## Load Test (k6)

Ramps up virtual users and reports detailed metrics.

```bash
cd performance
k6 run k6.js
# with custom config:
BASE_URL=http://localhost:5173 TEST_EMAIL=test@test.com k6 run k6.js
```

**Scenarios:**
- `load_test` — ramps 0→10→50→0 VUs over 50s
- `pike_test` — jumps to 100 VUs for 10s

**Thresholds (auto-fail if exceeded):**
- 95th percentile response time < 500ms
- 99th percentile response time < 1000ms
- Login latency 95th percentile < 300ms

## Interpreting Results

| Metric | Good | Needs Investigation |
|--------|------|---------------------|
| TTFB | < 50ms | > 200ms |
| p95 latency | < 200ms | > 500ms |
| p99 latency | < 500ms | > 1000ms |
| Error rate | 0% | > 1% |

## Where to Look for Bottlenecks

- **High TTFB** → Backend processing (DB queries, JWT signing)
- **High connect time** → Network or proxy (Vite dev proxy adds ~1-5ms)
- **Gradual degradation under load** → Connection pool exhaustion, rate limiting
- **Spike failures** → Rate limit filter (`RateLimitFilter.java` — 20 req/min per IP on `/api/auth/`)
