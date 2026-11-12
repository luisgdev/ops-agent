#!/usr/bin/env bash
# Demo of the assessment case: "Nov 4 volume was well above Nov 2, but revenue and margin
# went down. Why, how much did each cause cost us, and what should we do about it?"
#
# Needs only bash + curl (no jq, no python). Run against a server started with
#   ANTHROPIC_API_KEY=... mvn spring-boot:run      (from the project root)
#
# Usage:  bash scripts/demo-case.sh
# Env:    BASE=http://localhost:8080   server address
#         PAUSE=1                      wait for Enter between steps (handy for the video)
#         SKIP_DRYRUN=1                skip step 1
#         APPROVER=ops-lead            name recorded on the approval
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
APPROVER=${APPROVER:-ops-lead}
QUESTION='Call volume on Nov 4 was well above Nov 2, but revenue and margin went down. Why, how much did each cause cost us, and what should we do about it?'
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT

step()  { echo; echo "=================================================================="; echo "== $*"; echo "=================================================================="; }
pause() { if [ "${PAUSE:-0}" = "1" ]; then read -r -p "-- press Enter to continue " _ </dev/tty; fi; }

# Print the "answer" field of a saved /agent/ask response as readable text.
# Jackson pretty-prints the answer on one line, so sed can cut it out; printf %b turns \n into newlines.
show_answer() {
  sed -n 's/^ *"answer" *: *"\(.*\)",\{0,1\}$/\1/p' "$1" | sed 's/\\"/"/g; s#\\/#/#g' | while IFS= read -r line; do printf '%b\n' "$line"; done
}

# Compact summary of the fields we care about from a JSON listing of actions.
show_actions() {
  grep -E '^ *"(id|type|targetId|oldValue|newValue|status|reason)" *:' "$1" \
    | sed -E 's/^ +//; s/,$//' | sed -E 's/^"id"/\n"id"/' || true
}

ask() { # $1 = dryRun true|false
  curl -sS -X POST "$BASE/agent/ask" -H 'Content-Type: application/json' \
    -d "{\"question\": \"$QUESTION\", \"dryRun\": $1}" > "$TMP"
  if grep -q '"stopReason" *: *"error"' "$TMP"; then
    echo "The agent returned an error (is ANTHROPIC_API_KEY set on the server?):"; cat "$TMP"; exit 1
  fi
}

curl -sS -o /dev/null "$BASE/platform/buyers" || { echo "Server not reachable at $BASE. Start it with: mvn spring-boot:run"; exit 1; }

step "0. Starting state: buyer settings (Buyer South BUY-202 is priority 1, Buyer East BUY-203 cap is 75)"
curl -sS "$BASE/platform/buyers" > "$TMP"
grep -E '"(buyerId|name|priority|dailyCap|status)" *:' "$TMP" | sed -E 's/^ +//; s/,$//' | sed -E 's/^"buyerId"/\n"buyerId"/'
pause

if [ "${SKIP_DRYRUN:-0}" != "1" ]; then
  step "1. DRY RUN: same question, nothing may change (actions come back as DRY_RUN)"
  ask true
  echo "Question: $QUESTION"; echo
  show_answer "$TMP"
  echo; echo "-- actions it would have taken:"
  show_actions "$TMP"
  echo; echo "-- buyers are untouched:"
  curl -sS "$BASE/platform/buyers" | grep -E '"(buyerId|priority|dailyCap)" *:' | sed -E 's/^ +//; s/,$//' | paste -sd' ' | sed 's/"buyerId"/\n"buyerId"/g'
  pause
fi

step "2. REAL RUN: the agent investigates with tools, answers, and acts within guardrails"
ask false
echo "Question: $QUESTION"; echo
show_answer "$TMP"
pause

step "3. How it got there: tools the agent called (every number comes from these, not from the model)"
grep -E '^ *"tool" *:' "$TMP" | sed -E 's/^ +//; s/,$//' || true
echo; grep -E '"(iterations|stopReason)" *:' "$TMP" | sed -E 's/^ +//; s/,$//'
pause

step "4. Actions it took or proposed (AUTO_APPLIED vs PENDING_APPROVAL is the guardrail at work)"
curl -sS "$BASE/actions" > "$TMP"
show_actions "$TMP"
pause

step "5. Waiting for a person: pending approvals, with the policy's warnings"
curl -sS "$BASE/actions?status=PENDING_APPROVAL" > "$TMP"
cat "$TMP"
PENDING=$(grep -E '^ *"id" *:' "$TMP" | head -1 | sed -E 's/.*: *"([^"]+)".*/\1/' || true)
pause

if [ -n "$PENDING" ]; then
  step "6. A person approves $PENDING (Buyer East's cap is a commercial deal, so the agent could not do it alone)"
  curl -sS -X POST "$BASE/actions/$PENDING/approve" -H 'Content-Type: application/json' \
    -d "{\"approvedBy\":\"$APPROVER\",\"note\":\"Buyer East agreed by phone\"}" | grep -E '"(id|status|oldValue|newValue|decidedBy)" *:' | sed -E 's/^ +//; s/,$//'
  echo; echo "-- approving it a second time is refused (HTTP 409):"
  curl -sS -o /dev/null -w 'HTTP %{http_code}\n' -X POST "$BASE/actions/$PENDING/approve" \
    -H 'Content-Type: application/json' -d "{\"approvedBy\":\"$APPROVER\"}"
  pause
else
  step "6. Nothing pending to approve (the agent proposed no commercial change this run)"
fi

step "7. Result: current buyer and publisher settings"
curl -sS "$BASE/platform/buyers" | grep -E '"(buyerId|priority|dailyCap|status)" *:' | sed -E 's/^ +//; s/,$//' | paste -sd' ' | sed 's/"buyerId"/\n"buyerId"/g'
echo
curl -sS "$BASE/platform/publishers" | grep -iE '"(publisherId|status|flagged|flaggedForReview)" *:' | sed -E 's/^ +//; s/,$//' | paste -sd' ' | sed 's/"publisherId"/\n"publisherId"/g'
pause

step "8. Audit log: every outcome with its reason (also written to audit/audit-log.jsonl)"
curl -sS "$BASE/audit" | grep -E '^ *"(event|actionId|type|targetId|actor)" *:' | sed -E 's/^ +//; s/,$//' | paste -sd' ' | sed 's/"event"/\n"event"/g'
echo
