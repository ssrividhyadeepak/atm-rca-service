#!/usr/bin/env bash
# End-to-end demo against a running service: monitoring run -> RCA report -> incident draft
# -> approval -> incident. Start the service first (java -jar build/libs/bank-rca-service.jar).
#
#   scripts/demo.sh                     local service on port 8090, security off
#   BASE=http://localhost:9090 scripts/demo.sh
#   RCA_TOKEN=... scripts/demo.sh       with security on: a token holding rca:read rca:write
#                                       incident:read incident:write incident:approve
set -euo pipefail

BASE="${BASE:-http://localhost:8090}"
AUTH=()
if [ -n "${RCA_TOKEN:-}" ]; then
  AUTH=(-H "Authorization: Bearer ${RCA_TOKEN}")
fi

call() { # method path [json body]
  # ${AUTH[@]+...}: an empty array is an error under 'set -u' in the bash that ships with macOS
  if [ $# -ge 3 ]; then
    curl -sS -X "$1" ${AUTH[@]+"${AUTH[@]}"} -H "Content-Type: application/json" -d "$3" "${BASE}$2"
  else
    curl -sS -X "$1" ${AUTH[@]+"${AUTH[@]}"} "${BASE}$2"
  fi
}
field() { python3 -c "import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1], {'d': d}))" "$1"; }

echo "1. Monitoring run: fetch the failure events, correlate, analyse"
call POST /api/runs | field "'   %s: %s failure events in %s signatures from %s' % (d['status'], d['failedTransactions'], d['signatures'], d['source'])"

echo "2. The RCA report"
call GET /api/rca/latest | field "'   ' + d['headline'] + chr(10) + chr(10).join('   %d. %-6s %-22s %-13s %s (%d events)' % (f['rank'], f['severity'], f['category'], f['component'], (f['exception'] or f['title']).split('.')[-1], f['count']) for f in d['findings'])"

echo "3. Finding 1 in detail"
call POST /api/tools/getFinding '{"rank":1}' | field "'   Likely cause: ' + d['likelyCause'][:230] + chr(10) + '   Next step:    ' + d['suggestedAction'][:200] + chr(10) + '   Source:       ' + str((d.get('source') or {}).get('path')) + ':' + str((d.get('source') or {}).get('line')) + '  suspect commit ' + str(d['suspectCommit'])"

echo "4. Draft an incident for finding 1 (nothing is sent yet)"
DRAFT=$(call POST /api/incidents/drafts '{"rank":1}')
echo "$DRAFT" | field "'   %s  %s' % (d['draft']['id'], d['message'])"
echo "$DRAFT" | field "'   %s | priority %s | assigned to %s' % (d['draft']['shortDescription'], d['draft']['priority'], d['draft']['assignmentGroup'])"
ID=$(echo "$DRAFT" | field "d['draft']['id']")

echo "5. Drafting the same finding again does not make a second incident"
call POST /api/incidents/drafts '{"rank":1}' | field "'   created=%s: %s' % (d['created'], d['message'])"

echo "6. A person approves; only now is the incident created"
call POST "/api/incidents/${ID}/approve" '{"comment":"Confirmed with the on-call engineer"}' | field "'   %s  %s in %s, approved by %s' % (d['status'], d['incidentNumber'], d['incidentSystem'], d['decidedBy']) if d['status'] == 'SUBMITTED' else '   %s: %s' % (d['status'], d.get('lastError') or d.get('error'))"

echo "7. The audit trail of the incident"
grep "\"draft\":\"${ID}\"" "${RCA_LOG_DIR:-logs}/audit.log" 2>/dev/null | python3 -c "
import json,sys
for line in sys.stdin:
    e=json.loads(line); print('   %s  %-17s by %-12s -> %s %s' % (e['ts'][:19], e['action'], e['client'], e['status'], e.get('incident','')))" || echo "   (audit log not found here; see logs/audit.log where the service runs)"
