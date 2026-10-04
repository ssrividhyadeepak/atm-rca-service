# ATM RCA service

Monitors failed ATM transactions, correlates them, produces a root cause analysis (RCA)
report and drafts an incident for approval. Built in ten runnable steps; see the plan below.

## Two ways to run

| | Local (no profile) | Prod (`--spring.profiles.active=prod`) |
|---|---|---|
| Storage | In memory, lost on restart | MongoDB, from `MONGODB_URI` |
| Outside connections | None | Each one configured by environment variables |

The service prints its mode and connections when it starts. With the prod profile it refuses
to start if a required setting is missing or a connection cannot be made.

## Run locally

Needs JDK 17 or later and, the first time, network access for the Gradle dependencies.

```bash
./gradlew bootJar
java -jar build/libs/atm-rca-service.jar
```

Then, in another terminal:

```bash
curl -s http://localhost:8090/actuator/health
```

```bash
curl -s -X POST http://localhost:8090/api/runs
```

```bash
curl -s http://localhost:8090/api/runs
```

Stop with Ctrl+C.

## Run in the enterprise environment

1. Copy `config/prod.env.example` to `config/prod.env` and set `MONGODB_URI`.
2. Load the settings and start with the prod profile:

```bash
set -a; source config/prod.env; set +a
java -jar build/libs/atm-rca-service.jar --spring.profiles.active=prod
```

The startup lines show `Storage:  MongoDB database atm_rca`, and `/actuator/health` shows
the same under `storage`. If MongoDB cannot be reached within 10 seconds the service stops
with a message naming the host it tried.

| Variable | Required in prod | Meaning |
|---|---|---|
| `MONGODB_URI` | yes | MongoDB connection string |
| `MONGODB_DATABASE` | no (`atm_rca`) | Database name |
| `RCA_PORT` | no (`8090`) | HTTP port |
| `RCA_BIND_ADDRESS` | no (`127.0.0.1`) | Address to listen on |
| `RCA_MONITOR_ENABLED` | no (`true`) | Scheduled monitoring run on or off |
| `RCA_MONITOR_CRON` | no (every 15 minutes) | Schedule, UTC |

The API has no authentication yet (Day 9), which is why it listens on localhost only.

## Tests

```bash
./gradlew test
```

The MongoDB tests download and start a real `mongod`; they are skipped on a machine where
that is not possible.

## Plan

| Day | Step | Status |
|---|---|---|
| 1 | Foundation: config, storage (in-memory / MongoDB), scheduler, health | done |
| 2 | Splunk monitoring: retrieve failed transactions | |
| 3 | RCA input contract: sanitized `daily-rca-input.json` | |
| 4 | Failure correlation by exception, service, time, correlation ID | |
| 5 | RCA engine without an LLM: `RcaAnalyzer` + rule-based implementation | |
| 6 | RAG over runbooks and past RCAs | |
| 7 | Tools the LLM can call | |
| 8 | MCP server exposing the tools | |
| 9 | Enterprise controls: authN/Z, rate limiting, validation, guardrails, audit | |
| 10 | Incident workflow: RCA, incident draft, approval, mock incident | |
