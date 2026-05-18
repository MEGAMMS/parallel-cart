# Load Distribution Strategy (Requirement 5)

## Strategy Used
- **Nginx upstream with default round-robin** across two Spring Boot app instances:
  - `app-1`
  - `app-2`

Round-robin was selected because:
1. It provides simple and predictable balancing across homogeneous app nodes.
2. It avoids sticky-session bias for this stateless API use case.
3. It is easy to verify with response headers and upstream logs.

## Implementation Files
- Compose overlay with 2 app instances + nginx:
  - `docker-compose.load.yml`
- Nginx upstream config:
  - `infra/nginx/load-balancer.conf`
- Response instance marker header (`X-App-Instance`):
  - `src/main/java/com/parallelcart/config/InstanceIdResponseHeaderFilter.java`

## How Distribution Is Verified
The verification flow sends repeated requests through Nginx and checks:
1. `X-App-Instance` response header values include at least **two distinct instances**.
2. Nginx logs show requests forwarded to both upstream nodes.

One-command verifier:
```bash
./scripts/verify_first_thursday.sh
```

Generated evidence files:
- `reports/interviews/first-thursday/ft1-req5-load.log`
- `reports/interviews/first-thursday/FT1_FULL_VERIFICATION.md`
