# Bank RCA service

Monitors failed bank transactions, correlates them, produces a root cause analysis (RCA)
report and drafts an incident for approval. Built in ten runnable steps; see the plan below.

## Two ways to run

| | Local (no profile) | Prod (`--spring.profiles.active=prod`) |
|---|---|---|
| Storage | In memory, lost on restart | MongoDB, from `MONGODB_URI` |
| Failed transactions | A synthetic day of bank failures | Your Splunk, from `SPLUNK_URL` |
| Application code | Sample source in a JSON file | Your git repositories, from `GIT_REMOTE_URL` |
| Outside connections | None | Each one configured by environment variables |

The service prints its mode and connections when it starts. With the prod profile it refuses
to start if a required setting is missing or a connection cannot be made.

## Run locally

Needs JDK 17 or later and, the first time, network access for the Gradle dependencies.

```bash
./gradlew bootJar
java -jar build/libs/bank-rca-service.jar
```

Then, in another terminal:

```bash
curl -s http://localhost:8090/actuator/health
```

Retrieve the failure events of the last 24 hours (or fewer: `?hours=6`). Each one carries
its component, pod, tracing metadata (bank id, trace id, session id), exception and stack trace:

```bash
curl -s http://localhost:8090/api/failures
```

Group them into distinct problems (signatures), each with its count, share, time pattern,
pods and bank ids affected, and sample trace ids:

```bash
curl -s http://localhost:8090/api/correlation
```

Read the RCA report of the day, as markdown or JSON, or produce a fresh one now:

```bash
curl -s http://localhost:8090/api/rca/latest.md
```

```bash
curl -s http://localhost:8090/api/rca/latest
```

```bash
curl -s -X POST http://localhost:8090/api/rca
```

Start a monitoring run now, and list the recorded runs:

```bash
curl -s -X POST http://localhost:8090/api/runs
```

```bash
curl -s http://localhost:8090/api/runs
```

Locally the data is a synthetic day of 163 failure events in the shape the container
platform writes them, which correlation groups into 8 signatures. A run also happens at
startup and every 15 minutes.

### How events are grouped

Two events are the same problem when they have the same component, the same exception
class and the same message once the parts that change per request are replaced: the
event's own trace, session and bank ids, other ids, masked values and numbers.

| Field | Meaning |
|---|---|
| `count`, `percentOfComponent` | How many events, and their share of that component's failures |
| `timePattern` | `BURST`: nearly all within a quarter of the window. `STEADY`: across at least half of it. `SCATTERED`: in between. `FEW`: under five events |
| `firstSeen`, `lastSeen`, `peakHour` | When it happened |
| `pods`, `bankIds` | How widely: one pod points at an instance, all pods at code or a dependency |
| `sampleTraceIds`, `sampleStackTrace` | Where to look next |
| `id` | Stable for the same problem, so it can be recognised on another day |

`chains` lists requests that failed in more than one component, found by trace id, in
the order the components logged.

Stop with Ctrl+C.

## Run in the enterprise environment

1. Copy `config/prod.env.example` to `config/prod.env` and set `MONGODB_URI`, `SPLUNK_URL`,
   `SPLUNK_USERNAME` and `GIT_REMOTE_URL`.
2. Copy `config/application-prod.example.yml` to `config/application-prod.yml` and set your
   Kubernetes namespace and the containers to monitor, with a transaction label for each.
3. Load the settings, type your Splunk password (it is not echoed or saved), and start
   with the prod profile, from this folder:

```bash
set -a; source config/prod.env; set +a
read -s SPLUNK_PASSWORD && export SPLUNK_PASSWORD
read -s GIT_TOKEN && export GIT_TOKEN
java -jar build/libs/bank-rca-service.jar --spring.profiles.active=prod
```

The startup lines show `Storage:  MongoDB database bank_rca` and `Splunk:   Splunk https://...`,
followed by the first monitoring run with the number of failed transactions it retrieved.

- If MongoDB cannot be reached within 10 seconds the service stops with a message naming
  the host it tried.
- If Splunk cannot be reached, the service stays up, the run is recorded as FAILED with
  the reason, and `/actuator/health` shows `monitoring` DOWN until a run succeeds.
- If Splunk rejects the username, password or token, the service does not try again until
  it is restarted, so the scheduled runs cannot lock your account.
- If the git remote cannot be cloned at startup the service stops and names the repository.
  A later fetch that fails is logged, and lookups carry on against what was fetched last.
- To try only one real connection at a time, leave the profile off and set
  `RCA_STORAGE=mongo`, `SPLUNK_MODE=live` or `RCA_SOURCE_MODE=git` with its variables.

| Variable | Required in prod | Meaning |
|---|---|---|
| `MONGODB_URI` | yes | MongoDB connection string |
| `MONGODB_DATABASE` | no (`bank_rca`) | Database name |
| `SPLUNK_URL` | yes | Splunk management URL (port 8089), https |
| `SPLUNK_USERNAME`, `SPLUNK_PASSWORD` | yes, or a token | Your Splunk login. The service logs in once and uses the session Splunk returns |
| `SPLUNK_TOKEN` | instead of username and password | Splunk authentication token, where allowed |
| `SPLUNK_INDEX` | no (`main`) | Index to search |
| `GIT_REMOTE_URL` | yes, or `RCA_REPO_DIR`, or a repository list | Git remote to mirror, https |
| `GIT_TOKEN`, `GIT_USERNAME` | for private remotes | Read-only access token and the username the git host expects |
| `RCA_DEPLOYED_REF` | no (`main`) | Branch, tag or commit production runs |
| `RCA_REPO_DIR` | instead of a remote | An existing clone to read |
| `RCA_NAMESPACE` | no (`prod`) | Kubernetes namespace whose events are searched |
| `RCA_INPUT_DIR` | no (`data/rca-input`) | Where the daily RCA input files are written |
| `RCA_PORT` | no (`8090`) | HTTP port |
| `RCA_BIND_ADDRESS` | no (`127.0.0.1`) | Address to listen on |
| `RCA_MONITOR_ENABLED` | no (`true`) | Scheduled monitoring run on or off |
| `RCA_MONITOR_CRON` | no (every 15 minutes) | Schedule, UTC |

### How the RCA report is made

Every monitoring run ends by writing the day's report, with no LLM: a fixed set of rules
is applied to each signature, and each finding lists the rules that fired and the
evidence they used. One report is kept per day; a later run replaces it.

| Category | Rule |
|---|---|
| `PROPAGATED` | Another component logged the same requests first (same trace id) |
| `CODE_DEFECT` | The exception is one the code itself throws, such as `NullPointerException` |
| `DOWNSTREAM_TIMEOUT` | The message or stack trace says a call timed out |
| `DOWNSTREAM_UNAVAILABLE` | A 5xx status, or a refused or reset connection |
| `EXPECTED_CONDITION` | A business condition raised as an exception (insufficient, rejected, invalid) |
| `HANDLED_FALLBACK` | A fallback line with no exception class |
| `CLIENT_UI` | A UI event |
| `UNCLASSIFIED` | Nothing matched: needs a person |

Severity is by volume (HIGH from 50 events or a quarter of the day's failures, MEDIUM from
10), then a code defect of 10 or more is raised to HIGH and propagated, expected, fallback
and UI findings are lowered to LOW. A finding is `NEW` when its signature was first seen
inside the current window, `RECURRING` with the first-seen date otherwise. Locally that
history is in memory, so after a restart everything is new again.

### Checking the stack trace against the code

For every signature with a stack trace, the root cause's first application frame is looked
up in the source code as deployed: the file, the line, the code around it, the commit that
last changed the line and the latest commits to the file. The report then names a suspect:

| Rule | When |
|---|---|
| `R11-line-recently-changed` | The failing line was changed 14 days or fewer before the first failure |
| `R10-file-recently-changed` | The line is old, but its file was changed 7 days or fewer before the first failure |

A commit made after the failures began is never a suspect. When the class is not in the
repository, or the line is beyond the end of the file (a sign the deployed version is
different), the finding says so instead of guessing.

Locally the source comes from `config/stub-source.json`, which you can edit like the stub
failures: for each class, the lines around the places the sample failures point at, and its
commits with `hoursAgo` so they stay in step with the synthetic day. With the prod profile
it is git, read-only:

- One repository: set `GIT_REMOTE_URL` (mirrored at startup and fetched again every 15
  minutes) or `RCA_REPO_DIR` (an existing clone, read as it is).
- Several repositories: list them under `rca.source.repositories` in
  `config/application-prod.yml`, each with the components whose code it holds.
- `RCA_DEPLOYED_REF` is the branch, tag or commit production runs. Line numbers only mean
  something against that version.

To try a lookup on its own, which is also the quickest check of the git settings:

```bash
printf 'x\n\tat com.example.bank.deposit.validation.DepositValidator.validate(DepositValidator.java:35)\n' | curl -s -X POST "http://localhost:8090/api/source/locate?component=deposit-p1" -H "Content-Type: text/plain" --data-binary @-
```

### The daily RCA input

Before the analyzer runs, everything it is allowed to see is written to one file,
`data/rca-input/daily-rca-input-<day>.json` (the latest also as `daily-rca-input.json`),
and kept in storage. It is the boundary of what may leave the service: grouped signatures
with masked text, at most 50 of them, and stack traces cut down to their exception lines
and application frames.

| Use | How |
|---|---|
| See what the analyzer was given | `GET /api/rca/input` |
| Check its shape | `GET /api/rca/input/schema`, a JSON Schema (version `1.1`, which added `sources`; `1.0` inputs can still be replayed); a test fails if the service writes anything the schema does not describe |
| Trace a report back to its input | The report carries `inputHash`, the SHA-256 of the saved file |
| Re-run the analysis on a past or edited day | `POST /api/rca/replay` with an input as the body; nothing is read from Splunk and nothing is saved |

```bash
curl -s http://localhost:8090/api/rca/input > day.json
curl -s -X POST http://localhost:8090/api/rca/replay -H "Content-Type: application/json" --data @day.json
```

### Changing the sample data

Local runs take their failures from `config/stub-failures.json`. Edit it and the next run
uses the new contents; no restart is needed. Each entry is one kind of failure:

| Field | Meaning |
|---|---|
| `component` | Container that logs it (required) |
| `logger` | Class named in the log line (required) |
| `message` | Log text (required). `{int:1-4}` becomes a random number in that range |
| `count` | How many events to generate (required) |
| `level` | `ERROR` when left out |
| `exception` | Fully qualified exception class; leave out for a line with no exception |
| `stackTrace` | The lines under the exception, as a list |
| `fromHoursAgo`, `toHoursAgo` | When it happens, in hours before now; the whole day when left out |
| `uiEvent` | `true` for the UI format: no trace id, bank id and session in the text |
| `alsoLoggedBy` | Other components that log a line for the same request, with the same trace id |
| `note` | A comment for whoever edits the file |

`namespace`, `cluster`, `datacenter` and `bankIds` at the top apply to every event. A new
component only shows up in results if it is also listed under `rca.monitor.components`.
If the file has a mistake, the run is recorded as FAILED and says what to fix. To use a
file somewhere else, set `RCA_STUB_FILE`.

### What is read from each event

The search returns raw events; the service takes them apart itself. It expects the JSON
the container platform writes, with the application's log line in `message`:

```
<time> -- LEVEL: <level> <logger class> <number> -[<thread>] --<bank id>-<trace id>- <text>
```

| Field | Taken from |
|---|---|
| component, pod, namespace | `kubernetes.container_name`, `pod_name`, `namespace_name` |
| cluster, host | `openshift.labels.clustername`, `hostname` |
| level, logger, thread | the log line prefix |
| bankId, traceId | the `--<bank id>-<trace id>-` part; on UI events, `BANK ID:` in the text |
| sessionId | `CustomerTrackingSessionId:` in the text |
| exception | the first fully qualified `...Exception` or `...Error` class in the text |
| stackTrace | everything from the first `at ...(` frame |
| transaction | the label configured for the component |

The label in front of the id on UI events is a setting, `rca.parser.id-label` (or
`RCA_ID_LABEL`), `BANK ID` by default. Set it to the label your own UI events use.

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
| 3 | RCA input contract: sanitized `daily-rca-input.json` with a JSON Schema | done |
| 4 | Failure correlation by exception, component, time and trace id | done |
| 5 | RCA engine without an LLM: `RcaAnalyzer` + rule-based implementation | done |
| 6 | RAG over runbooks and past RCAs | |
| 7 | Tools the LLM can call | |
| 8 | MCP server exposing the tools | |
| 9 | Enterprise controls: authN/Z, rate limiting, validation, guardrails, audit | |
| 10 | Incident workflow: RCA, incident draft, approval, mock incident | |
