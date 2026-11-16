# Rya Ops Agent

A small Java 17 / Spring Boot service with a Claude-powered operations agent. It watches a
call-routing platform (an in-memory model of the three-day data kit), finds where money is
being lost and fixes what it safely can. Everything else goes to a person for approval.

- **All the numbers are computed in Java.** The model reads tool results and never does the arithmetic.
- **Every change goes through one guarded path:** a policy decides whether it is applied
  automatically, needs approval, or is blocked. Each one is written to an audit log, and
  there is a dry-run mode.
- **The agent loop uses Claude tool use over plain `java.net.http`** (no SDK). It has hard
  limits on iterations and tool calls, and recovers from bad tool calls.

---

## 1. How to run it

**Requirements:** JDK 17+, Maven 3.9+, and a Claude API key.

```bash
export ANTHROPIC_API_KEY=sk-ant-...          # never committed; read from the environment
export ANTHROPIC_MODEL=claude-opus-5-5       # optional, default is claude-sonnet-5-5

mvn test                                     # no API key needed
mvn spring-boot:run                          # starts on http://localhost:8080
```

Ask the question:

```bash
curl -s -X POST localhost:8080/agent/ask -H 'Content-Type: application/json' -d '{
  "question": "Call volume on Nov 4 was well above Nov 2, but revenue and margin went down. Why, how much did each cause cost us, and what should we do about it?"
}'
```

Add `"dryRun": true` to see what the agent would do without changing anything.
`scripts/demo.sh` runs the full flow: dry run, then the real run, then pending actions,
approve/reject, the audit log and the current settings.

### Command line (no server needed)

```bash
export ANTHROPIC_API_KEY=sk-ant-...
scripts/ask.sh                                   # asks the Nov 4 question above
scripts/ask.sh --html report.html                # same, and also saves an HTML report
scripts/ask.sh --dry-run "Why did BUY-202 fail on Nov 4?"
```

It prints a readable report: the answer rendered for the terminal (tables, lists, bold),
then each action with its status (APPLIED, NEEDS APPROVAL, DRY RUN, BLOCKED), its reason, policy
note and warnings, then tokens, time and request id. The rendering lives in the `cli` package
only; the service still returns the model's Markdown unchanged.

| Flag | Effect |
|---|---|
| `--dry-run` | Record what the agent would do, change nothing |
| `--raw` | Print the model's Markdown as is |
| `--verbose` | Also list every tool call with its input and duration |
| `--no-color` | Plain text (automatic when piped or when `NO_COLOR` is set) |
| `--out file.json` | Save the full JSON response |
| `--html report.html` | Save a self-contained HTML report: no JavaScript, no external files, light/dark theme. Open it in a browser, or use Print → Save as PDF |

The script runs from the project folder, so a relative `--html` or `--out` path is saved there;
use an absolute path to save elsewhere. Flags alone are fine: with no question it asks the one above.

Pending actions are approved or rejected through the REST API (`mvn spring-boot:run`). The CLI
keeps no state between runs apart from `audit/audit-log.jsonl`.

### Use it as an MCP server

The same tools the agent uses are available over the [Model Context Protocol](https://modelcontextprotocol.io)
(stdio), so Claude Code, Claude Desktop, IntelliJ or any MCP client can investigate the
platform with **its own model**. This mode needs **no API key**.

**Requirements:** JDK 17+ and Maven 3.9+ (the script builds the jar on first use).

1. Check that it builds and starts, from the project folder:
   ```bash
   scripts/mcp.sh      # prints "rya-ops-agent MCP server ready: 15 tools ..." on stderr; Ctrl+D to stop
   ```
2. Register it with your client. Use an **absolute path** to `scripts/mcp.sh` everywhere except
   the project-scoped `.mcp.json`.

   | Client | Setup |
   |---|---|
   | Claude Code, inside this repo | Nothing to do: `.mcp.json` already declares `rya-ops-agent`. Start `claude` here and approve the server when asked |
   | Claude Code, any folder | `claude mcp add rya-ops-agent --scope user -- /abs/path/to/ops-agent/scripts/mcp.sh` |
   | Claude Desktop | Add the block below to `claude_desktop_config.json`, then restart the app |
   | IntelliJ IDEA / other clients | Add a stdio server whose command is the absolute path of `scripts/mcp.sh` |

   ```json
   {
     "mcpServers": {
       "rya-ops-agent": {
         "command": "/abs/path/to/ops-agent/scripts/mcp.sh",
         "env": { "OPSAGENT_DRY_RUN": "false" }
       }
     }
   }
   ```
3. Verify: in Claude Code run `/mcp` (it should list `rya-ops-agent` as connected), then ask
   *"Use the rya-ops-agent tools to explain why margin fell on Nov 4 compared with Nov 2."*

Settings are environment variables (set them in the `env` block, or in `claude mcp add -e KEY=value`):

| Variable | Default | Meaning |
|---|---|---|
| `OPSAGENT_DRY_RUN` | `false` | `true`: action tools only record what they would do. Good for a first try |
| `OPSAGENT_AUTO_APPLY` | `true` | `false`: kill switch, every action needs approval |
| `OPSAGENT_DATA_DIR` | `data` | Data kit folder (relative to the project folder) |
| `OPSAGENT_AUDIT_FILE` | `audit/audit-log.jsonl` | Audit file (empty for memory only) |

What it exposes: all 15 tools (10 read, 5 action), marked read-only or not, plus short
instructions for the client's model (investigate first, quote tool numbers, check coverage
before changing a buyer). Action tools use the same policy, audit log and dry run as the
REST API. **Approve/reject is not an MCP tool on purpose**, so a model cannot approve its own
proposals: a person does it through `POST /actions/{id}/approve` on the REST server.

Limits: the platform is in memory and each MCP session starts from the original data kit, so
pending actions live only as long as the client keeps the server running; the audit file is the
durable record. Logs go to stderr; stdout carries only protocol messages.

### API

| Method | Path | What it does |
|---|---|---|
| POST | `/agent/ask` | `{question, dryRun?}` → `{answer, toolCalls[], actions[], stopReason, iterations, usage}` |
| GET | `/actions?status=PENDING_APPROVAL` | Lists actions, optionally filtered by status |
| GET | `/actions/{id}` | Returns one action |
| POST | `/actions/{id}/approve` | `{approvedBy, note?}` applies it (409 if the action is not pending) |
| POST | `/actions/{id}/reject` | `{rejectedBy, reason}` changes nothing and records the decision |
| GET | `/audit` | Every action event, oldest first. Also written to `audit/audit-log.jsonl` |
| GET | `/platform/buyers`, `/platform/publishers` | Current settings, to see the effect of applied actions |

Errors come back as JSON: 400 for bad input, 404 for an unknown id, 409 for the wrong state.

### Configuration (`application.properties`)

| Property | Default | Meaning |
|---|---|---|
| `opsagent.data-dir` | `data` | Folder with the data kit |
| `opsagent.auto-apply-enabled` | `true` | Kill switch. Set it to `false` and every action needs approval |
| `opsagent.max-iterations` | `12` | Maximum model calls per question. The last one has tools disabled, so the model must answer |
| `opsagent.max-tool-calls` | `30` | Maximum tool executions per question |
| `opsagent.audit-file` | `audit/audit-log.jsonl` | Append-only audit file (leave empty to keep it in memory only) |
| `anthropic.model` | `claude-sonnet-5-5` | Model, overridden by `ANTHROPIC_MODEL` |

---

## 2. The agent's answer to the question

Run with `claude-opus-5-5`: 4 model calls, 12 tool calls, about 35k input and 3k output
tokens. The full JSON response, including every tool call and its input, is in
[`docs/sample-run-nov4-question.json`](docs/sample-run-nov4-question.json).

> Nov 4 had 120 more calls than Nov 2, but margin fell by **$501** ($4,722 → $4,221) and revenue by **$1,215**. Most of the extra calls didn't turn into payable calls. The main reasons are that **Buyer South (BUY-202) stopped answering** and **Social Affiliate D (PUB-104) sent junk traffic**. Two smaller problems also wasted volume: Buyer East hit its cap, and some calls came from states where we have no buyer.
>
> | Cause | Evidence | Est. lost revenue | Est. lost margin |
> |---|---|---|---|
> | **BUY-202 Buyer South not answering** (lost money) | Failed 42.9% of 177 routed calls (76 no-answer or hangup) vs 11.2% on Nov 2. Its pings still said about 5.6 agents were free. Failures were heavy from 9:00 on (10 no-answers at 9:00 and again at 10:00). | $1,699.76 | $630.80 |
> | **PUB-104 junk traffic** (lost money) | 140 calls (vs 80) but only 37 payable (vs 57). 22 duplicates, 52 calls under 30s, median length 18s (vs 529s). Its calls also used up BUY-205's cap, which was reached at 16:38, so good calls from other publishers were turned away. | $744.43 | $276.50 |
> | **BUY-203 Buyer East cap reached** (extra volume not captured) | Cap of 75 reached at 13:53. 15 FL/VA calls rejected, and no other buyer covers those. | $355.01 | $119.33 |
> | **No buyer licensed in AZ** (extra volume not captured) | 10 more AZ calls than Nov 2 couldn't be sold (17 AZ calls in total on Nov 4). | $291.27 | $102.82 |
>
> **How this matches the actual change:** The four causes add up to **$1,129.45** of lost margin. That's more than the actual drop of $501 because the rest of the business grew: without these causes, margin would have been **$628.45 higher** than Nov 2. CTV Network B is an example, with margin up $183. Each call is counted under only one cause: a buyer's failed calls count against the buyer, and a publisher is charged only for its own traffic quality, including good calls it pushed out at a cap. The cap and AZ causes didn't cut what we earned on Nov 2. They explain why the extra volume didn't become revenue.
>
> **Actions taken**
> - **ACT-0001 – APPLIED:** Moved BUY-202 down one priority level, from 1 to 2. I didn't pause it because it's the only buyer for 7 state/campaign combinations (NC, SC, TN, and MS on CTV), which carried 92 calls on Nov 4. At priority 2, BUY-201, BUY-203 and BUY-204 get its GA, MS-search and TX calls first, and BUY-202 still takes the calls nobody else can.
> - **ACT-0002 – APPLIED:** Flagged PUB-104 for human review. This doesn't change routing.
>
> **Actions waiting for approval**
> - **ACT-0003 – PENDING APPROVAL:** Raise BUY-203's daily cap from 75 to 90. That's the cap the tool says would have taken every call rejected at the cap on Nov 4. Approve only after Buyer East agrees to take the extra calls.
>
> **Recommended for the team**
> - **Call Buyer South:** its pings say agents are free while 43% of calls go unanswered. Get them to fix staffing or their ping integration before moving them back to priority 1.
> - **PUB-104 review:** ask about the duplicates and the 18-second median call length. If it doesn't improve, consider pausing it (needs approval) or getting refunds or holdbacks.
> - **AZ coverage:** sign a buyer licensed in AZ for the Medicare Advantage Search and CTV campaigns.
> - **More FL/VA capacity:** sign another CTV buyer there. BUY-204 and BUY-205 are licensed in those states but don't buy CTV. Also watch BUY-205's cap once PUB-104 is under review.
>
> **Caveat:** These figures are estimates of what would have happened if each problem hadn't occurred, based on how things ran on Nov 2. They are not booked losses.

**Tools it called:** `get_platform_overview`, `compare_days`, `explain_margin_change` and
`list_actions` came first, as one parallel batch. Then it drilled into `get_buyer_hourly` and
`check_buyer_coverage` for BUY-202, `get_publisher_quality` for PUB-104, `get_unrouted_calls`
and `get_buyer_performance`. Finally it called the three action tools.

### My review of that answer

The dollar figures match an independent pandas calculation over the same CSV to the cent,
and `MetricsServiceTest` pins them. Two places where the model's wording goes slightly past
what the tools say:

- **"BUY-204 gets MS-search calls first."** BUY-204 is also priority 2, so after the demotion
  BUY-202 and BUY-204 *tie* on MS search. The kit does not define how ties are broken. A
  routing simulator tool would make this exact (see section 5).
- **"BUY-204 is licensed in those states."** BUY-204 covers FL but not VA. The tool output
  says this correctly; the model's sentence merges FL and VA.

An earlier run with `claude-sonnet-5-5` reached the same four causes and the same two applied
actions. It also showed the error handling at work: its first parallel calls used 2025
dates, got a clear error listing the available days, and it retried with the right ones.
That run surfaced three things I then fixed:
1. It claimed the causes "overlap". They are built not to, and the tool now says so explicitly.
2. It did not propose the cap change, because no tool gave a number. The tool now returns
   `capNeededToTakeAllRejectedCallsOnTarget`.
3. It guessed dates. The available days are now in the system prompt.

---

## 3. What the data shows and how each cost is computed

All of this is computed by `MetricsService.explainMarginChange(baseline, target)`. Each cause
is a counterfactual: *if this one thing had behaved as on the baseline day, how many more
payable calls would we have had?* Each lost payable call is valued at the baseline revenue
and margin per payable call of the buyer, publisher or campaign involved. Unrouted and
unanswered calls cost $0 in payout, so the loss is margin not earned.

| Cause | Type | How it is detected | How lost payable calls are estimated |
|---|---|---|---|
| Buyer not answering (BUY-202) | Decline driver | Failure rate (NO_ANSWER + BUYER_HANGUP) up at least 10 pp vs baseline, with at least 5 excess failures | `failed(T) − failRate(B) × routed(T)`, then × the baseline payable rate when answered |
| Publisher junk (PUB-104) | Decline driver | Quality flags: duplicates ≥ 5%, calls under 30s ≥ 20% of routed, payable rate < 50% | `min(payable(B) − payable(T), connected(T) × drop in payable rate)`, plus clean calls from other publishers rejected at a cap the junk filled |
| Buyer cap exhausted (BUY-203) | Missed upside | NOT_ROUTED_CAP calls from clean publishers above the baseline count | Extra rejected calls × the campaign's baseline payable rate per routed call |
| No buyer for a state (AZ) | Missed upside | NOT_ROUTED_NO_BUYER calls above the baseline count, per state | Same as the cap cause |

**No double counting.** The obvious trap is Search Partner A (PUB-101). It had 20 fewer
payable calls on Nov 4, but its traffic was clean: those calls went to Buyer South, which
didn't answer. So publishers are charged only from *quality* signals, never from a raw
shortfall. In the same way, the calls PUB-104 lost to the cap it filled are already inside
PUB-104's own figure.

**Reconciliation.** Actual margin change: −$501.00. Estimated lost margin: $1,129.45. The
difference, +$628.45, is what volume growth elsewhere would have added; CTV traffic was up
60 calls. The tool returns this split so the model doesn't have to compute it.

---

## 4. Design choices

```
 POST /agent/ask ─► AgentService (loop) ─► Claude Messages API
                        │  tool_use / tool_result
                        ▼
                   ToolRegistry  ── validates input against each tool's JSON Schema
                    │         │
          read tools│         │action tools
                    ▼         ▼
          MetricsService   ActionService ─► ActionPolicy (AUTO / NEEDS_APPROVAL / BLOCK)
                    │         │          └► AuditLog (memory + JSONL)
                    ▼         ▼
                  PlatformStore (config: mutable through ActionService only; history: read-only)

 POST /actions/{id}/approve|reject ─► ActionService (re-checks the value is unchanged, then applies)
```

**Numbers come from code, not the model.** Read tools return computed metrics, never raw
rows. `explain_margin_change` returns dollar figures, totals and the reconciliation, and the
system prompt tells the model to quote them rather than compute. Each cause also carries its
`method` string, so the model can explain *how* a number was built.

**Tools.** There are 10 read tools and 5 action tools. The read tools were designed around
the decisions an ops person makes, not around the data files:

- `check_buyer_coverage` exists because the most important judgment call here is *don't
  pause Buyer South*. It is the only buyer for NC, SC and TN, and pausing it would strand 92
  calls a day.
- `get_buyer_hourly` checks whether the availability pings match reality.

**Guardrails (`ActionPolicy`).** The agent acts alone only when a change is small,
reversible and cannot leave a caller without a buyer.

| Action | Decision |
|---|---|
| Flag a publisher for review | Automatic. It changes no routing |
| Lower a buyer's priority by one level | Automatic, at most once per buyer per 24h. The buyer stays as a fallback |
| Any other priority change (promotion, more than one level) | Needs approval |
| Change a cap | Needs approval, because a cap is a commercial agreement. It warns to confirm with the buyer, and it is blocked if the value is ≤ 0 or more than a 3x change |
| Pause or resume a buyer | Needs approval. It warns which states would have no buyer |
| Pause or resume a publisher | Needs approval |
| Reason shorter than 20 characters, out-of-range value, no-op | Blocked |

A few more protections around this:

- **Kill switch.** Setting `auto-apply-enabled=false` sends everything to approval.
- **Duplicate proposals.** A change that is already pending is returned instead of being
  queued again.
- **Stale check.** On approve, the action is applied only if the setting still has the value
  it had when proposed. Otherwise it becomes `FAILED_STALE`.
- **Dry run.** Nothing is applied or queued, but the attempt is still audited, and the
  result says "would be applied automatically" or "would be sent for approval".

**Agent loop safety.**

- An unknown tool, a schema violation (missing field, wrong type, invented parameter, bad
  enum) or an exception in a tool all go back to the model as an `is_error` tool result with
  a message it can act on.
- `max-iterations` caps model calls. On the last call `tool_choice: none` forces a final
  answer, and a text block tells the model why.
- `max-tool-calls` caps parallel tool spam.
- If the Claude API fails mid-run, the response still lists the actions that were already
  applied.
- 429, 529 and 5xx responses are retried with backoff.
- Tool definitions are prompt-cached across iterations.

**Why no SDK and a thin Spring layer.** The whole loop is one class of about 180 lines over
`java.net.http`, so every request field is visible and easy to change live. The core
(platform, metrics, tools, policy, agent) is plain Java with no Spring imports. `OpsAgent`
wires it, Spring only exposes it over HTTP, and `cli/AskCli` runs the same thing from the
command line, with a terminal-friendly rendering of the answer.

---

## 5. Tests

Run them with `mvn test`. They need no API key.

| Test | What it protects |
|---|---|
| `MetricsServiceTest` | The dollar figures in the answer (cross-checked independently), the flags, cap timing, coverage, and no causes when a day is compared with itself |
| `ActionServiceTest` | Every guardrail rule: auto vs approval vs block, the 24h cooldown, kill switch, dry run, approve/reject, stale check, duplicate proposals, sole-coverage warning |
| `AgentServiceTest` | The loop against a scripted fake Claude: results sent back, unknown tool and bad input recovered from, iteration limit with forced answer, real action statuses reported, dry run, API failure mid-run still reporting applied actions |
| `ApiControllerTest` | HTTP approve/reject flow and error codes (MockMvc) |
| `McpServerTest` | The MCP protocol subset: handshake, tool list and hints, calls, tool errors vs protocol errors, dry run, no approval tool |
| `PlatformLoaderTest` | The data kit loads completely, and `*` and blank fields are handled |

---

## 6. What I'd change for production / with more time

- **Routing simulator tool.** Replay a day with a proposed config to show exactly where calls
  would go, including tie-breaks, before an action is approved. It would also let the policy
  auto-approve changes whose simulated impact is small.
- **Real integration.** Today the platform is in memory and changes are lost on restart; the
  audit file is the only durable record. In production, the read tools would query the
  platform's API or warehouse, and actions would call its API with idempotency keys. A
  database would hold actions and audit (append-only, with the request id tying an action
  to the conversation that proposed it).
- **AuthN/AuthZ.** Approvals should come from authenticated users with roles, not a free-text
  `approvedBy` field. Rate-limit `/agent/ask`.
- **Rollback.** Add a "revert action" endpoint (old values are already recorded) and
  auto-expiring changes. For example, the priority demotion could revert after 24h unless
  renewed.
- **Better attribution.** Use a multi-day baseline (or same weekday last week) instead of a
  single day, add confidence intervals, and use hour-level windows (Buyer South started
  failing at 9:00).
- **Monitoring mode.** Run the analysis on a schedule or on threshold alerts (for example a
  failure-rate spike vs pings) instead of only on questions, and notify the ops channel with
  pending approvals.
- **Evals.** Build a set of scripted platform scenarios (buyer outage, junk burst, cap
  exhaustion, no problem at all) and check that the agent finds the right cause, quotes tool
  numbers, takes only allowed actions, and does nothing when nothing is wrong.
- **Cost controls.** Per-request token budget, model routing (a cheaper model for simple
  questions), and streaming responses.

---

## Project layout

```
src/main/java/digital/rya/opsagent/
  platform/   Call, Buyer, Publisher, Campaign, AvailabilityPing, PlatformStore, PlatformLoader
  metrics/    MetricsService (every number), Reports (result records)
  actions/    ActionPolicy (guardrails), ActionService, AuditLog, PlatformAction, ActionType, ActionStatus
  tools/      OpsTools (tool definitions), ToolRegistry (validation + safe execution), Schema
  agent/      AgentService (the loop), AnthropicMessagesApi, SystemPrompt, AgentResponse
  config/     OpsAgent (plain-Java wiring), AppConfig (Spring beans)
  web/        AgentController, ActionController, PlatformController, ApiErrors
  mcp/        McpServer (MCP over stdio), McpMain (entry point), Instructions
  cli/        AskCli (ask from the command line), MarkdownRenderer (terminal output), HtmlReport (HTML export)
data/         the data kit, unchanged
docs/         full JSON of the run shown above
scripts/      demo.sh (REST flow), ask.sh (command-line report), mcp.sh (MCP server)
```
