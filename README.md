# Bank RCA service

Monitors failed bank transactions, correlates them, produces a root cause analysis (RCA)
report and drafts an incident for approval. Built in ten runnable steps; see the plan below.

## Two ways to run

| | Local (no profile) | Prod (`--spring.profiles.active=prod`) |
|---|---|---|
| Storage | In memory, lost on restart | MongoDB, from `MONGODB_URI` |
| Failed transactions | A synthetic day of bank failures | Your Splunk, from `SPLUNK_URL` |
| Application code | Sample source in a JSON file | Your git repositories, from `GIT_REMOTE_URL` |
| Runbooks and past RCAs | Samples in `config/knowledge/` | Your folder, from `RCA_KNOWLEDGE_DIR`, plus every report the service saves |
| Who may call | Anyone on this machine; no tokens | Bearer tokens from your identity provider, from `RCA_ISSUER_URI` |
| Incidents | Mock: recorded, not sent | Mock until `RCA_INCIDENT_MODE=servicenow` is set on purpose |
| Outside connections | None | Each one configured by environment variables |

The service prints its mode and connections when it starts. With the prod profile it refuses
to start if a required setting is missing or a connection cannot be made.

## Run locally

Needs JDK 17 or later and, the first time, network access for the Gradle dependencies and
the embedding model (about 90 MB).

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
   `SPLUNK_USERNAME`, `GIT_REMOTE_URL` and `RCA_ISSUER_URI`.
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
| `RCA_ISSUER_URI` | yes | Identity provider that issues the bearer tokens |
| `RCA_RESOURCE_URL` | when behind a proxy | Public URL of this service |
| `RCA_INCIDENT_MODE` | no (`mock`) | `servicenow` to create real incidents on approval |
| `SERVICENOW_URL`, `SERVICENOW_USERNAME`, `SERVICENOW_PASSWORD` or `SERVICENOW_TOKEN` | with `servicenow` | The ServiceNow instance and an account that may create incidents |
| `RCA_NAMESPACE` | no (`prod`) | Kubernetes namespace whose events are searched |
| `RCA_INPUT_DIR` | no (`data/rca-input`) | Where the daily RCA input files are written |
| `RCA_LOG_DIR` | no (`logs`) | Where `audit.log`, the record of every tool call, is written |
| `RCA_KNOWLEDGE_DIR` | no (`config/knowledge`) | Folder with `runbooks/` and `past-rcas/` |
| `RCA_KNOWLEDGE_MODE` | no (`embedding`) | `embedding` or `keyword` |
| `RCA_MODEL_CACHE_DIR` | no (`.model-cache`) | Where the embedding model is kept |
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

### Runbooks and past RCAs (RAG)

Each finding is matched against a knowledge base: runbooks and past RCAs, as markdown files
in `config/knowledge/runbooks/` and `config/knowledge/past-rcas/`. The findings of every
RCA report the service saves are added to the past RCAs by themselves, so the history grows.

Matching has two steps, and the result says which one found the document:

| `matchedBy` | Meaning |
|---|---|
| `EXACT` | The document is written for the same exception class (its `Exceptions:` or `Exception:` line) |
| `SEMANTIC` | No such document; this one reads alike. It comes with a similarity score from 0 to 1 and is attached only at 0.51 or above |

Semantic search uses a local embedding model (all-MiniLM-L6-v2) and Spring AI's in-memory
vector store. Nothing leaves the machine. The model, about 90 MB, is downloaded once into
`.model-cache/`. If it cannot be loaded - no download access - the service starts anyway on
keyword search and says so in the log; set `RCA_KNOWLEDGE_MODE=keyword` to choose that.

A matched runbook adds its mitigation to the finding's next step; matched past RCAs add
their root cause and resolution to the evidence. To search by hand:

```bash
curl -s -G http://localhost:8090/api/knowledge/search --data-urlencode "q=deposits not credited, the ledger is unavailable" --data-urlencode "type=runbook"
```

Use `type=past-rca` for the history. After adding or editing files,
`curl -s -X POST http://localhost:8090/api/knowledge/reload`.

To add a runbook, copy one in `config/knowledge/runbooks/`: a title, the `- Key: value`
lines (`Id`, `Components`, `Exceptions`), then `## Signals`, `## Triage` and
`## Mitigation` sections. `evals/knowledge-cases.json` holds the questions the search is
tested with; add a case when you add a document.

### Tools an assistant can call

Five functions - four read-only, one that creates an incident draft - each with a name, a description and a JSON Schema for its
arguments generated from the method signature:

| Tool | Arguments | Returns |
|---|---|---|
| `getFailureSummary` | none | The latest analysis in brief: headline and one line per finding |
| `getFinding` | `rank` | Everything about one finding: cause, next step, evidence, source line, runbook |
| `lookupRunbook` | `query`, `limit` (optional) | Verdict `MATCH` or `NO_MATCH`, and the runbooks with how they matched |
| `searchHistoricalRca` | `query`, `limit` (optional) | The same for past RCAs, excluding the day being analysed |
| `draftIncident` | `rank`, `note` (optional) | An incident draft for the finding. The only tool that changes anything; it sends nothing |

List them with their input and output schemas and call counts, or call one by name with
JSON arguments - what a model does, done by hand:

```bash
curl -s http://localhost:8090/api/tools
```

```bash
curl -s -X POST http://localhost:8090/api/tools/lookupRunbook -H "Content-Type: application/json" -d '{"query":"LedgerPostingException"}'
```

- **Validation:** a call with a bad argument is refused with a message that says what to
  send instead (`'rank' must be between 1 and 8`), so a model can correct itself. An unknown
  tool is a 404; arguments of the wrong type or invalid JSON are a 400.
- **Audit:** every call is one JSON line in `logs/audit.log`: time, call id, tool, masked
  arguments, duration and outcome (`OK`, `REJECTED` or `ERROR`).

### Following one request across services

Two tools start an investigation of a single failed transaction (branch `feature/rca-agent-mcp`,
plan in [docs/agent-enhancement.md](docs/agent-enhancement.md)):

| Tool | Scope | What it returns |
|---|---|---|
| `splunkFindFailures` | `logs:read` | Recent failures, newest first, each with its trace id. Filters: `hours`, `transaction`, `component`, `bankId`, `exception`, `limit` |
| `splunkTraceRequest` | `logs:read` | Every log line of one trace id, in time order, across components |

```bash
curl -s -X POST localhost:8090/api/tools/splunkFindFailures \
  -H 'Content-Type: application/json' -d '{"exception":"HostAuthTimeoutException","limit":3}'
```

```bash
curl -s localhost:8090/api/traces/TRACE_ID_FROM_ABOVE
```

A trace has:

- `steps`: each line as `IN` (request received), `OUT` (call to a dependency), `OUT_RESP`
  (its answer), `RESP` (response sent), `EXCEPTION` or `LOG`, with component, status,
  latency and the logged body.
- `calls`: each `OUT` paired with its `OUT_RESP`, as `OK`, `FAILED` or `NO_RESPONSE`.
- `divergence`: where the request left the normal path, decided by fixed rules in
  `TraceService`, not by a model: `TIMEOUT`, `DOWNSTREAM_ERROR`, `DECLINED`, `NULL_FIELD`,
  `MISSING_FIELD` or `EXCEPTION`, with the step that shows it.

Bodies are masked by `PayloadMasker` before they leave the service: any field whose name
contains `pan`, `card`, `account`, `email`, `phone`, `ssn`, `pin`, `token` and similar becomes
`***`, and every other string still goes through the pattern masker. Field names and nulls
are kept, because a null in a request is the evidence.

In stub mode the lines come from the `trace` list of an entry in `config/stub-failures.json`
(`{amount}` is one value for the whole request, `{bankId}` the entry's bank id). In live mode
the `trace_events` search in `application.yml` fetches every event carrying the trace id.

**Assumption to check against real logs:** the step kinds are read from lines that start
`IN ...`, `OUT <target> <operation> ...`, `OUT_RESP <target> <operation> status=...`,
`RESP status=...`, with an optional ` payload={json}`. The real applications will log this
differently; the patterns at the top of `TraceService` are the one place to change. Lines
that do not match still appear in the trace as `LOG`, and exceptions are always recognised.

### What changed: change requests

`serviceNowRecentChanges` (scope `change:read`) lists the change requests for one component
and, given the time the failures started, how each sits against it:

```bash
curl -s "localhost:8090/api/changes?component=withdrawal-p1&hours=72&failureTime=2026-10-06T19:00:00Z"
```

| `timing` | Meaning |
|---|---|
| `BEFORE_FAILURE` | Finished before the failures began; `minutesBeforeFailure` says how long. Listed closest first |
| `DURING_FAILURE_START` | In progress when they began |
| `AFTER_FAILURE` | Started later, so it is not the cause (often the fix or the rollback) |

The timing is arithmetic done in `ChangeService`; whether a change is the cause is left to
the reader. Descriptions are masked and cut to 600 characters.

| Mode | Set | Reads from |
|---|---|---|
| stub (default, also with the prod profile) | nothing | `config/stub-changes.json`, read on every call; times are hours before now |
| real | `RCA_CHANGE_MODE=servicenow` and the `SERVICENOW_*` settings | ServiceNow's `change_request` table |

ServiceNow's Table API is ordinary REST: one URL per table, `/api/now/table/<table>`, with
the filter in `sysparm_query`. The real client makes one `GET` and writes nothing:

```
GET /api/now/table/change_request
    ?sysparm_query=cmdb_ci.nameIN<names>^start_date<=<to>^end_date>=<from>^ORDERBYDESCstart_date
    &sysparm_fields=number,short_description,description,type,state,risk,cmdb_ci,assignment_group,start_date,end_date,work_start,work_end,close_code
    &sysparm_display_value=all&sysparm_limit=50
```

**To check on the real instance:** a change is matched by its configuration item's name.
Where that differs from the container name in the logs, map it under `rca.change.ci-names`
in `application.yml` (for example `withdrawal-p1: "Withdrawal Service"`). The account needs
read access to `change_request` (role `itil` or `sn_change_read`); it uses the same
connection settings as incident submission, and turning this on does not turn that on.

### Which commit: ranked suspects

`gitFindSuspects` (scope `code:read`) lists the commits that reached the deployed branch
before a failure began and ranks them:

```bash
curl -s "localhost:8090/api/source/suspects?component=withdrawal-p1&failureTime=2026-10-06T19:00:00Z&failingClass=com.example.bank.withdrawal.host.HostAuthClient"
```

| Points | For |
|---|---|
| 50 / 40 / 30 / 18 / 8 / 3 | Committed within 1 hour / 6 hours / 1 day / 3 days / 7 days / longer before the failure |
| 35 | Changes the failing class (`failingClass`, the first application frame of the stack trace) |
| 15 | Changes what runs: code, config or build files. A commit of only tests or docs gets 0 |

Each suspect carries its `score`, the `reasons` behind it, the files changed, their `kinds`
(`CODE`, `CONFIG`, `BUILD`, `TEST`, `DOCS`), and the pull request (`#412`) or change number
(`CHG0030101`) when the commit message names one. The rules are in `SuspectService`; the
weights are a first guess and have not been tuned on real incidents.

Two limits to keep in mind:

- **Commit time is not deployment time.** A commit is a suspect only if it was deployed
  before the failure; `serviceNowRecentChanges` is the check.
- **Pull requests are read from commit messages only.** JGit sees the repository, not the
  git host, so titles, reviewers and approvals are not available.

In stub mode commits come from `config/stub-source.json` (a file entry may now leave out
`className`, for a config file, and may list the `components` it belongs to). In git mode
they are read from the deployed ref of the repositories configured for the component.

### The assistant

`POST /api/assistant/ask` answers a question by letting a chat model call those tools. The
answer comes with the tool calls that produced it.

```bash
curl -s -X POST http://localhost:8090/api/assistant/ask -H "Content-Type: application/json" -d '{"question":"Is there a runbook for HostAuthTimeoutException, and has it happened before?"}'
```

There is no LLM behind it yet. The model is a scripted stand-in (`ScriptedChatModel`) that
picks tools by keywords and fills in fixed templates: it proves the loop works - question,
tool calls, tool results, answer - and understands nothing. The loop itself
(`RcaAssistant`) is written against Spring AI's `ChatModel` and `ToolCallingManager`, so a
real model replaces the stand-in by adding a Spring AI model starter and its settings;
nothing else changes. The loop stops a model that keeps calling tools after four rounds,
masks the question, and sends the model the system prompt in
`src/main/resources/prompts/rca-assistant.md`.

### MCP: the tools for Copilot and other clients

The same five tools are served over the Model Context Protocol at `/mcp` (Streamable HTTP,
stateless), together with a prompt, `daily_rca`, that tells a client's model how to put
together the day's briefing from them. An MCP call goes through the same validation, scope
check, rate limit and audit line as any other tool call, and a refused call comes back as a
tool error the model can read and correct.

With the service running locally (security `off`), connect a client:

- **Copilot in VS Code:** open this folder; `.vscode/mcp.json` already points at
  `http://localhost:8090/mcp`. Start the server from that file, switch Copilot Chat to agent
  mode, and ask "what failed today?" or run the `daily_rca` prompt.
- **Claude Code:** start `claude` in this folder; `.mcp.json` is picked up.

With security `dev` or `jwt` the client must send a bearer token. In `.vscode/mcp.json`:

```json
{
  "inputs": [{ "type": "promptString", "id": "rca-token", "description": "Bearer token", "password": true }],
  "servers": {
    "bank-rca-service": {
      "type": "http",
      "url": "http://localhost:8090/mcp",
      "headers": { "Authorization": "Bearer ${input:rca-token}" }
    }
  }
}
```

To check the endpoint without a client:

```bash
curl -s -X POST http://localhost:8090/mcp -H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

### From a finding to an incident

```
finding --draft--> DRAFT --approve--> APPROVED --submit--> SUBMITTED (incident number)
                     \--reject--> REJECTED
```

1. **Draft.** `POST /api/incidents/drafts` with `{"rank": 1}`, or the `draftIncident` tool
   from the assistant or an MCP client. The text is built from the finding: what failed,
   the likely cause with its confidence, the next step, the evidence, the suspect commit and
   the runbook. Priority comes from the severity (HIGH is "2 - High"; nothing is raised as
   critical automatically) and the assignment group from the runbook's owner.
2. **Review.** `GET /api/incidents/{id}` shows the draft. Nothing has been sent.
3. **Decide.** `POST /api/incidents/{id}/approve` creates the incident;
   `POST /api/incidents/{id}/reject` with a `reason` drops it.

What keeps this safe:

- **A person approves.** Approval is an endpoint with its own scope, `incident:approve`,
  and is not offered as a tool, so no assistant can approve. With security on, whoever
  approves must not be the client that drafted.
- **One problem, one incident.** Drafting a finding that already has an open or submitted
  incident from the last 7 days returns that one.
- **A finding that only repeats another component's failure is refused,** with a pointer
  to the origin.
- **A note from the assistant is checked:** it may only mention ids, files, commits and
  exceptions that are in the finding.
- **If the ticket system is down,** the approval is kept, the draft stays APPROVED with the
  reason, and `POST /api/incidents/{id}/submit` tries again. The draft id is sent as the
  correlation id and looked up first, so a retry cannot create a duplicate.
- **Every step is on the audit trail:** who drafted, who approved or rejected, and the
  incident number.

The ticket system is a mock by default, also with the prod profile: the incident gets a
number and what would have been sent is appended to `data/incidents/mock-incidents.jsonl`.
Real ServiceNow incidents are created only after setting `RCA_INCIDENT_MODE=servicenow`
with `SERVICENOW_URL` and credentials.

### End-to-end demo

With the service running, one script walks through the whole flow - monitoring run, RCA
report, a finding in detail, incident draft, the duplicate check, approval and the audit
trail:

```bash
scripts/demo.sh
```

With security on, give it a token that holds `rca:read rca:write incident:read
incident:write incident:approve` in `RCA_TOKEN`; note that the four-eyes rule will then
refuse the approval step, because the script drafts and approves as the same client.

### Security, rate limits and audit

`rca.security.mode` (or `RCA_SECURITY_MODE`) decides who may call:

| Mode | Meaning |
|---|---|
| `off` | No tokens. The local default. Refused with the prod profile, and refused if the service listens on anything but this machine |
| `dev` | Every request needs a bearer token signed with a local key: `./gradlew -q devToken` |
| `jwt` | Every request needs a bearer token from your identity provider (`RCA_ISSUER_URI`). The prod profile always uses this |

With `dev` and `jwt` the token's signature, expiry, issuer and audience
(`bank-rca-service`) are checked, and each endpoint and each tool needs a scope:

| Scope | Opens |
|---|---|
| `rca:read` | Reports, the RCA input, runs, correlation; the `getFailureSummary` and `getFinding` tools |
| `rca:write` | Starting a run or an analysis, replaying an input, reloading the knowledge base |
| `logs:read` | `/api/failures`: the failure events themselves |
| `kb:read` | Knowledge search; the `lookupRunbook` and `searchHistoricalRca` tools |
| `code:read` | `/api/source/locate` |
| `change:read` | Change requests |
| `incident:read` | Incident drafts and what became of them |
| `incident:write` | Drafting an incident; the `draftIncident` tool. Sends nothing |
| `incident:approve` | Approving or rejecting a draft. Approval creates the incident: for people, not assistants |

Listing tools, asking the assistant and connecting over MCP need any valid token; the assistant then acts with
the caller's scopes, so a tool the caller may not use is refused to the assistant too.
`/actuator/health` needs no token and, in prod, gives the status only.

To try it locally:

```bash
RCA_SECURITY_MODE=dev java -jar build/libs/bank-rca-service.jar
```

```bash
export RCA_TOKEN=$(./gradlew -q devToken)
curl -s -H "Authorization: Bearer $RCA_TOKEN" http://localhost:8090/api/rca/latest.md
```

That token is read-only and lasts 8 hours. For one that may also start runs:
`./gradlew -q devToken --args="me 'rca:read rca:write logs:read kb:read code:read' 8"`.

- **Rate limits**, per client per minute: 12 for calls that run a Splunk search, 20 for
  assistant questions, 60 for any one tool, 120 for everything else. Over the limit the
  answer is 429 with `Retry-After`. Set under `rca.rate-limit`.
- **Trace ids:** each request gets one, taken from a W3C `traceparent` header when the
  caller sends it. It is returned as `X-Trace-Id`, and is on every log line and audit entry
  of that request.
- **Audit:** `logs/audit.log` has one JSON line per tool call with the client, trace id,
  tool, masked arguments, duration and outcome: `OK`, `REJECTED` (bad arguments), `DENIED`
  (missing scope), `RATE_LIMITED` or `ERROR`.
- **Assistant guardrails:** the question is length-limited and masked; only the registered
  read-only tools can be called; a model is stopped after four rounds of tool calls; and
  its answer goes out only if every runbook id, RCA id, commit, file, exception name and
  trace id in it came from a tool result or the question. Otherwise the plain answer built
  from the tool results is returned in its place, with `grounded: false` and the made-up
  references listed under `withheld`.

### The daily RCA input

Before the analyzer runs, everything it is allowed to see is written to one file,
`data/rca-input/daily-rca-input-<day>.json` (the latest also as `daily-rca-input.json`),
and kept in storage. It is the boundary of what may leave the service: grouped signatures
with masked text, at most 50 of them, and stack traces cut down to their exception lines
and application frames.

| Use | How |
|---|---|
| See what the analyzer was given | `GET /api/rca/input` |
| Check its shape | `GET /api/rca/input/schema`, a JSON Schema (version `1.2`; `1.1` added `sources` and `1.2` added `knowledge`, and earlier inputs can still be replayed); a test fails if the service writes anything the schema does not describe |
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

Locally the API needs no token, which is why it listens on localhost only; see "Security,
rate limits and audit" below.
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
| 6 | RAG over runbooks and past RCAs | done |
| 7 | Tools a model can call, with a scripted stand-in for the model | done |
| 8 | MCP server exposing the tools | done |
| 9 | Enterprise controls: authN/Z, rate limiting, validation, guardrails, audit | done |
| 10 | Incident workflow: RCA, incident draft, approval, mock incident | done |
