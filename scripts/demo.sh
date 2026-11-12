#!/usr/bin/env bash
# Demo against a running server (mvn spring-boot:run). Needs curl; jq is optional.
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
pretty() { if command -v jq >/dev/null; then jq "$@"; else cat; fi; }

QUESTION='Call volume on Nov 4 was well above Nov 2, but revenue and margin went down. Why, how much did each cause cost us, and what should we do about it?'

echo "== 1. Dry run: what would the agent do? (changes nothing)"
curl -s -X POST "$BASE/agent/ask" -H 'Content-Type: application/json' \
  -d "{\"question\": \"$QUESTION\", \"dryRun\": true}" | pretty '{answer, actions: [.actions[] | {id, type, targetId, status}]}'

echo "== 2. Real run"
curl -s -X POST "$BASE/agent/ask" -H 'Content-Type: application/json' \
  -d "{\"question\": \"$QUESTION\"}" | pretty '{answer, tools: [.toolCalls[].tool], actions: [.actions[] | {id, type, targetId, oldValue, newValue, status}]}'

echo "== 3. What is waiting for a person?"
curl -s "$BASE/actions?status=PENDING_APPROVAL" | pretty '[.[] | {id, type, targetId, oldValue, newValue, reason, warnings}]'

echo "== 4. Approve one (replace ACT-0003 with an id from step 3)"
echo "curl -s -X POST $BASE/actions/ACT-0003/approve -H 'Content-Type: application/json' -d '{\"approvedBy\":\"ops-lead\",\"note\":\"Buyer East agreed by phone\"}'"
echo "   ...or reject it:"
echo "curl -s -X POST $BASE/actions/ACT-0003/reject -H 'Content-Type: application/json' -d '{\"rejectedBy\":\"ops-lead\",\"reason\":\"Buyer East has no extra agents\"}'"

echo "== 5. Audit log and current buyer settings"
curl -s "$BASE/audit" | pretty '[.[] | {at, event, actionId, type, targetId, oldValue, newValue, actor}]'
curl -s "$BASE/platform/buyers" | pretty '[.[] | {buyerId, priority, dailyCap, status}]'
