# URL shortener

Signed-in customers turn long URLs into short links; anyone may click one and is
redirected; every click is counted exactly and reported back to the link's owner.

The service is a Spring Boot 3.5 / Java 21 application in [`service/`](service),
backed by PostgreSQL and Redis. It was produced by the agentic SDLC orchestrator
in [`orchestrator/`](orchestrator) against the frozen contract in
[`artifacts/openapi.yaml`](artifacts/openapi.yaml).

## Quick start

```bash
docker compose up -d postgres
docker run -d --name shortener-redis -p 6379:6379 redis:7-alpine
cd service && ./mvnw spring-boot:run

curl -s -X POST http://localhost:8080/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"alice-dev-password"}'
```

Note that `docker-compose.yml` starts PostgreSQL only.

## Documentation

| Page | For |
|---|---|
| [docs/API.md](docs/API.md) | Callers: every endpoint, working `curl` commands, error codes, edge behaviour |
| [docs/RUNBOOK.md](docs/RUNBOOK.md) | Operators: configuration and defaults, health endpoints, failure modes |
| [artifacts/openapi.yaml](artifacts/openapi.yaml) | The machine-readable API contract |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Running the orchestrator itself |

## Tests

```bash
cd service && ./mvnw verify   # Testcontainers; needs a reachable Docker daemon
pytest orchestrator/tests     # orchestrator unit tests
```
