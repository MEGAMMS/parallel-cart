# P6 Metrics Queries (Latency, Throughput, Error Rate, Consumer Lag)

## Scope
This document defines practical queries/commands for `P6-T2`:
- latency (`avg`, `max`; and p50/p95 when histogram backend is enabled)
- throughput (request count/rate)
- error rate (5xx count)
- Kafka consumer lag

## 1) Actuator Metrics Endpoints

List available metric names:

```bash
curl -s http://localhost:8080/actuator/metrics
```

Primary HTTP metric payload:

```bash
curl -s http://localhost:8080/actuator/metrics/http.server.requests
```

Useful fields from payload:
- `COUNT` -> total requests (throughput basis)
- `TOTAL_TIME` -> cumulative seconds (derive avg latency)
- `MAX` -> max latency in seconds

## 2) Snapshot Script

Use one command to collect the required metrics and consumer lag:

```bash
./scripts/metrics_snapshot_p6.sh
```

Output includes:
- request count
- computed average latency (ms)
- max latency
- HTTP 500 count
- raw `http.server.requests` payload
- `kafka-consumer-groups --describe` output (lag)

## 3) Optional Prometheus/Grafana Query Mapping

If Prometheus scraping is enabled later, these equivalents apply:

- Throughput (RPS):
  - `sum(rate(http_server_requests_seconds_count[1m]))`
- Error rate (5xx/s):
  - `sum(rate(http_server_requests_seconds_count{status=~"5.."}[1m]))`
- p95 latency:
  - `histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))`
- p50 latency:
  - `histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket[5m])) by (le))`

## 4) Consumer Lag Interpretation

From `kafka-consumer-groups --describe`:
- `CURRENT-OFFSET` close to `LOG-END-OFFSET` means low lag.
- Rising difference indicates consumer cannot keep up.

Track these groups:
- `invoice-workers`
- `notification-workers`

## 5) Load-test Workflow

1. Run traffic (k6 or concurrent curl).
2. Run `./scripts/metrics_snapshot_p6.sh`.
3. Save output under `reports/benchmark/` for before/after comparisons.
