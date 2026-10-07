# RCA agent enhancement (branch `feature/rca-agent-mcp`)

Goal: move from a service that analyses failures by fixed rules to an agent an LLM drives
through MCP - investigating one failed transaction end to end, with a developer in the loop -
while every data source stays stubbed for local runs and real behind the `prod` profile.

## How the idea maps to this service

- **The LLM is the MCP client.** There is no LLM API available to this service, so the
  model is whatever sits in the client: Copilot agent mode, Claude Code. The service gives
  it tools, a prompt, and guardrails; the investigation's reasoning happens in the client.
- **One MCP server, tools grouped by system.** The idea has separate Splunk, ServiceNow and
  Git MCP servers. Here they are tool groups on one server (`splunk...`, `serviceNow...`,
  `git...`), each backed by a stub locally and a real client in prod. A model sees the same
  thing either way; a vendor MCP server can replace a group later.
- **What the model writes comes back through a tool and is checked.** Hypotheses, a fix
  plan, a PR description: each is submitted through a tool that verifies the references in
  it against the evidence, as incident notes already are.
- **Nothing changes production without a person.** Drafts only; approval is never a tool.

## Steps

| # | Item | Tools | Status |
|---|---|---|---|
| 1 | Trigger on failure signals, capture request id and context | `splunkFindFailures` | done |
| 2 | Trace a request across services, in order | `splunkTraceRequest` | done |
| 3 | Lineage and payloads: where the transaction diverged | `splunkTraceRequest` (divergence) | done |
| 4 | Mask before the model sees anything; audit every action | all tools | done for traces: payloads masked by field name, both tools audited |
| 5 | Recent change requests for the services and window | `serviceNowRecentChanges` | done: stub file and real `change_request` client, timing against the failure |
| 6 | Commits, PRs and config changes ranked by closeness to the failure | `gitFindSuspects` | done: commits before the failure ranked by time, failing class and kind of file; PR and change numbers from commit messages only |
| 7 | Ranked hypotheses with evidence and a confidence score | `rcaCollectEvidence`, `rcaRecordHypotheses` | done: numbered evidence, citations checked, score computed by the service; `investigate_finding` prompt |
| 8 | Fix plan: remediation, test plan, blast radius | `rcaRecordFixPlan` | done: grounded text, blast radius within known components, edits checked against the deployed line |
| 9 | Multi-turn: the developer steers, the investigation remembers | `investigationGet`, `investigationExcludeEvidence`, `investigationAddNote` | done: state and history kept, hypotheses re-scored |
| 10 | Draft PR after approval, never an autonomous change | `gitDraftPullRequest` | done: refused until a person approves; mock by default, GitHub client behind `RCA_PR_MODE=github` |
