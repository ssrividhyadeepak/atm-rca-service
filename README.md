# ATM RCA service

Monitors failed ATM transactions, correlates them, produces a root cause analysis (RCA)
report and drafts an incident for approval. Built in ten runnable steps; see the plan below.

## Two ways to run

| | Local (no profile) | Prod (`--spring.profiles.active=prod`) |
|---|---|---|
| Storage | In memory, lost on restart | MongoDB, from `MONGODB_URI` |
| Failed transactions | A synthetic day of ATM failures | Your Splunk, from `SPLUNK_URL` |
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

Retrieve the failure events of the last 24 hours (or fewer: `?hours=6`). Each one carries
its component, pod, tracing metadata (ATM id, trace id, session id), exception and stack trace:

```bash
curl -s http://localhost:8090/api/failures
```

Start a monitoring run now, and list the recorded runs:

```bash
curl -s -X POST http://localhost:8090/api/runs
```

```bash
curl -s http://localhost:8090/api/runs
```

Locally the data is a synthetic day of 149 failure events in the shape the container
platform writes them: cash-withdrawal (75), cash-deposit (36), balance-inquiry (26) and
atm-ui (12). A run also happens at startup and every 15 minutes.

Stop with Ctrl+C.

## Run in the enterprise environment

1. Copy `config/prod.env.example` to `config/prod.env` and set `MONGODB_URI`, `SPLUNK_URL`
   and `SPLUNK_USERNAME`.
2. Copy `config/application-prod.example.yml` to `config/application-prod.yml` and set your
   Kubernetes namespace and the containers to monitor, with a transaction label for each.
3. Load the settings, type your Splunk password (it is not echoed or saved), and start
   with the prod profile, from this folder:

```bash
set -a; source config/prod.env; set +a
read -s SPLUNK_PASSWORD && export SPLUNK_PASSWORD
java -jar build/libs/atm-rca-service.jar --spring.profiles.active=prod
```

The startup lines show `Storage:  MongoDB database atm_rca` and `Splunk:   Splunk https://...`,
followed by the first monitoring run with the number of failed transactions it retrieved.

- If MongoDB cannot be reached within 10 seconds the service stops with a message naming
  the host it tried.
- If Splunk cannot be reached, the service stays up, the run is recorded as FAILED with
  the reason, and `/actuator/health` shows `monitoring` DOWN until a run succeeds.
- If Splunk rejects the username, password or token, the service does not try again until
  it is restarted, so the scheduled runs cannot lock your account.
- To try only one real connection at a time, leave the profile off and set
  `RCA_STORAGE=mongo` or `SPLUNK_MODE=live` with its variables.

| Variable | Required in prod | Meaning |
|---|---|---|
| `MONGODB_URI` | yes | MongoDB connection string |
| `MONGODB_DATABASE` | no (`atm_rca`) | Database name |
| `SPLUNK_URL` | yes | Splunk management URL (port 8089), https |
| `SPLUNK_USERNAME`, `SPLUNK_PASSWORD` | yes, or a token | Your Splunk login. The service logs in once and uses the session Splunk returns |
| `SPLUNK_TOKEN` | instead of username and password | Splunk authentication token, where allowed |
| `SPLUNK_INDEX` | no (`main`) | Index to search |
| `RCA_NAMESPACE` | no (`atm-prod`) | Kubernetes namespace whose events are searched |
| `RCA_PORT` | no (`8090`) | HTTP port |
| `RCA_BIND_ADDRESS` | no (`127.0.0.1`) | Address to listen on |
| `RCA_MONITOR_ENABLED` | no (`true`) | Scheduled monitoring run on or off |
| `RCA_MONITOR_CRON` | no (every 15 minutes) | Schedule, UTC |

### What is read from each event

The search returns raw events; the service takes them apart itself. It expects the JSON
the container platform writes, with the application's log line in `message`:

```
<time> -- LEVEL: <level> <logger class> <number> -[<thread>] --<ATM id>-<trace id>- <text>
```

| Field | Taken from |
|---|---|
| component, pod, namespace | `kubernetes.container_name`, `pod_name`, `namespace_name` |
| cluster, host | `openshift.labels.clustername`, `hostname` |
| level, logger, thread | the log line prefix |
| atmId, traceId | the `--<ATM id>-<trace id>-` part; on UI events, `ATM ID:` in the text |
| sessionId | `CustomerTrackingSessionId:` in the text |
| exception | the first fully qualified `...Exception` or `...Error` class in the text |
| stackTrace | everything from the first `at ...(` frame |
| transaction | the label configured for the component |

An event in another format is kept with whatever could be read, and counted under
`unparsed` in the response.

### Signing in to Splunk with your own login

The service talks to Splunk's management port (usually 8089), not the web page you log in
to. Two things decide whether your username and password work there:

- **The port must be reachable from your machine.** Check with the command below; it asks
  for your password itself. A JSON answer means it works; a timeout means the port is closed
  to you and a Splunk administrator has to open it or give you another route.
- **Splunk must know your password.** It does for Splunk and LDAP / Active Directory
  logins. If you reach Splunk through a single sign-on page (SAML), Splunk never sees a
  password, and the same command answers 401.

```bash
curl -s -u YOUR_USER_ID "https://splunk.example.com:8089/services/server/info?output_mode=json" | head -c 300
```

The API has no authentication yet (Day 9), which is why it listens on localhost only.
`/api/failures` returns log content: card numbers, account numbers and emails are masked,
by three patterns that were not written against your log formats.

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
| 2 | Splunk monitoring: retrieve failed transactions | done |
| 3 | RCA input contract: sanitized `daily-rca-input.json` | |
| 4 | Failure correlation by exception, service, time, correlation ID | |
| 5 | RCA engine without an LLM: `RcaAnalyzer` + rule-based implementation | |
| 6 | RAG over runbooks and past RCAs | |
| 7 | Tools the LLM can call | |
| 8 | MCP server exposing the tools | |
| 9 | Enterprise controls: authN/Z, rate limiting, validation, guardrails, audit | |
| 10 | Incident workflow: RCA, incident draft, approval, mock incident | |
