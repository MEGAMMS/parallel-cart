# Load Distribution Strategy (P5-T4)

## Topology
- Nginx reverse proxy (`nginx`) fronts two Spring app instances (`app-1`, `app-2`).
- Nginx listens on `:8088` and forwards traffic to `app-1:8080` / `app-2:8080`.
- Shared dependencies: PostgreSQL, Redis, Kafka.

## Strategy
- Algorithm: `least_conn`.
- Why:
  - Better fit than pure round-robin when request latency is uneven.
  - Protects against overloading one instance during long-running checkout operations.
  - Simple operational model with predictable behavior.

## Validation Signal
- Nginx response header `X-Upstream-Addr` exposes chosen backend.
- Repeated requests should show both backends over time.

## Operational Notes
- Start stack with:
  - `docker compose --profile lb up -d --build app-1 app-2 nginx`
- Health through balancer:
  - `curl http://localhost:8088/actuator/health`
- Scale option:
  - Add more app services in compose and include them in Nginx upstream.

## Tradeoffs
- Current setup is static upstream (compose-defined instances).
- No active health probing in Nginx config yet; failed upstream detection relies on connection failures.
- Sticky sessions not needed now because API is stateless and shared state is externalized.
