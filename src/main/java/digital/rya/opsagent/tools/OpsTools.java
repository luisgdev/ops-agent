package digital.rya.opsagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import digital.rya.opsagent.actions.ActionRequest;
import digital.rya.opsagent.actions.ActionService;
import digital.rya.opsagent.actions.ActionStatus;
import digital.rya.opsagent.actions.ActionType;
import digital.rya.opsagent.actions.PlatformAction;
import digital.rya.opsagent.metrics.MetricsService;
import digital.rya.opsagent.platform.PlatformStore;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Defines the agent's tools. Read tools return computed numbers (never raw call rows).
 * Action tools all go through {@link ActionService}, so guardrails and the audit log apply.
 */
public final class OpsTools {

    private static final String DAY = "Day as YYYY-MM-DD. Defaults to the latest day with data.";

    private OpsTools() {
    }

    public static List<Tool> all(MetricsService metrics, ActionService actions) {
        List<Tool> tools = new ArrayList<>();
        tools.addAll(readTools(metrics, actions));
        tools.addAll(actionTools(actions));
        return tools;
    }

    // =====================================================================================
    // READ TOOLS
    // =====================================================================================

    static List<Tool> readTools(MetricsService m, ActionService actions) {
        PlatformStore store = m.store();
        return List.of(
                new Tool("get_platform_overview",
                        "Current platform configuration: days with data, campaigns (payable threshold), buyers (campaigns, "
                                + "licensed states, daily cap, priority, revenue per payable call, status) and publishers "
                                + "(campaign, payout, status, review flag). Start here.",
                        Schema.object().build(), false,
                        (in, ctx) -> {
                            Map<String, Object> out = new LinkedHashMap<>();
                            out.put("daysWithData", store.days().stream().map(LocalDate::toString).toList());
                            out.put("routingRule", "A call goes to the ACTIVE buyer on that campaign who covers the caller's "
                                    + "state, has cap left and the best priority (1 = highest). Routed calls count against the cap.");
                            out.put("campaigns", store.campaigns());
                            out.put("buyers", store.buyers());
                            out.put("publishers", store.publishers());
                            return out;
                        }),

                new Tool("get_day_summary",
                        "Totals for one day: calls, calls by status, routed, payable, payable rate, revenue, payout, margin, margin per call.",
                        Schema.object().string("day", DAY, false).build(), false,
                        (in, ctx) -> m.daySummary(day(in, "day", store))),

                new Tool("compare_days",
                        "Compares two days: change in calls, revenue, payout, margin and calls by status, plus the same "
                                + "per buyer, per publisher and per campaign (sorted by margin change, worst first).",
                        Schema.object().string("baselineDay", "Earlier day, YYYY-MM-DD.", true)
                                .string("targetDay", "Later day, YYYY-MM-DD.", true).build(), false,
                        (in, ctx) -> m.compareDays(day(in, "baselineDay", store), day(in, "targetDay", store))),

                new Tool("get_buyer_performance",
                        "Per-buyer performance for a day: calls routed, payable, short, no-answer, hangup, failure rate, "
                                + "payable rate, revenue, margin, cap usage and the time the cap was reached, average agents the "
                                + "buyer reported free in its pings, and flags (HIGH_FAILURE_RATE, "
                                + "REPORTS_FREE_AGENTS_BUT_NOT_ANSWERING, CAP_REACHED, PAUSED). Omit buyerId for all buyers.",
                        Schema.object().string("day", DAY, false).string("buyerId", "e.g. BUY-202. Omit for all buyers.", false).build(), false,
                        (in, ctx) -> in.hasNonNull("buyerId")
                                ? m.buyerPerformance(day(in, "day", store), in.get("buyerId").asText())
                                : m.buyerPerformance(day(in, "day", store))),

                new Tool("get_buyer_hourly",
                        "Hour-by-hour for one buyer: calls routed, payable, no-answer, hangup and the agents it reported "
                                + "free. Use it to see when a buyer started failing and whether its pings match reality.",
                        Schema.object().string("buyerId", "e.g. BUY-202", true).string("day", DAY, false).build(), false,
                        (in, ctx) -> m.buyerHourly(day(in, "day", store), in.get("buyerId").asText())),

                new Tool("check_buyer_coverage",
                        "Before demoting or pausing a buyer: for each campaign and state it covers, lists the other active "
                                + "buyers that could take the call. 'soleCoverage' states would get NO buyer if it were paused.",
                        Schema.object().string("buyerId", "e.g. BUY-202", true).string("day", DAY, false).build(), false,
                        (in, ctx) -> m.buyerCoverage(in.get("buyerId").asText(), day(in, "day", store))),

                new Tool("get_publisher_quality",
                        "Traffic quality per publisher for a day: calls, payable, short, calls under 30s, duplicates, "
                                + "repeat callers, unrouted, buyer failures, payable rate, median duration, revenue, payout, "
                                + "margin per call and quality flags (HIGH_DUPLICATES, MANY_CALLS_UNDER_30S, LOW_PAYABLE_RATE). "
                                + "Omit publisherId for all publishers.",
                        Schema.object().string("day", DAY, false).string("publisherId", "e.g. PUB-104. Omit for all.", false).build(), false,
                        (in, ctx) -> in.hasNonNull("publisherId")
                                ? m.publisherQuality(day(in, "day", store), in.get("publisherId").asText())
                                : m.publisherQuality(day(in, "day", store))),

                new Tool("get_unrouted_calls",
                        "Calls that went to no buyer on a day, grouped by reason (NOT_ROUTED_CAP / NOT_ROUTED_NO_BUYER), "
                                + "campaign and caller state, with first/last time, publishers, and which buyers cover that "
                                + "state and campaign.",
                        Schema.object().string("day", DAY, false).build(), false,
                        (in, ctx) -> m.unroutedCalls(day(in, "day", store))),

                new Tool("explain_margin_change",
                        "Main diagnosis tool. Splits the margin change between two days into causes (buyer not answering, "
                                + "publisher junk traffic, buyer cap exhausted, no buyer for a state), each with evidence, "
                                + "estimated lost payable calls, lost revenue and lost margin in dollars, and the method used. "
                                + "Also returns totals and the part of the change the causes do not explain. Quote its dollar "
                                + "figures instead of computing your own.",
                        Schema.object().string("baselineDay", "Earlier 'normal' day, YYYY-MM-DD.", true)
                                .string("targetDay", "Day to explain, YYYY-MM-DD.", true).build(), false,
                        (in, ctx) -> m.explainMarginChange(day(in, "baselineDay", store), day(in, "targetDay", store))),

                new Tool("list_actions",
                        "Actions already submitted (applied, pending approval, blocked, rejected, dry-run). Check it so you do "
                                + "not propose the same change twice.",
                        Schema.object().enumString("status", "Optional filter.", false,
                                names(ActionStatus.values())).build(), false,
                        (in, ctx) -> actions.list(in.hasNonNull("status") ? ActionStatus.valueOf(in.get("status").asText()) : null))
        );
    }

    // =====================================================================================
    // ACTION TOOLS
    // =====================================================================================

    private static final String REASON = "Why, citing the numbers from the read tools that justify it. Required; goes into the audit log.";
    private static final String GUARDRAIL_NOTE = " The result says whether it was applied, sent for human approval, "
            + "blocked by policy, or only simulated (dry-run). Report that status honestly to the user.";

    static List<Tool> actionTools(ActionService actions) {
        return List.of(
                new Tool("set_buyer_priority",
                        "Change a buyer's routing priority (1 = gets calls first, up to 5). Demoting by exactly one level is "
                                + "applied automatically (once per buyer per 24h); other changes need human approval. Run "
                                + "check_buyer_coverage first." + GUARDRAIL_NOTE,
                        Schema.object().string("buyerId", "e.g. BUY-202", true)
                                .integer("priority", "New priority, 1-5.", true)
                                .string("reason", REASON, true).build(), true,
                        (in, ctx) -> submit(actions, ctx, ActionType.SET_BUYER_PRIORITY, in.get("buyerId").asText(),
                                in.get("priority").asText(), in.get("reason").asText())),

                new Tool("set_buyer_cap",
                        "Propose a new daily cap for a buyer. Always needs human approval (the buyer must agree to take more "
                                + "calls)." + GUARDRAIL_NOTE,
                        Schema.object().string("buyerId", "e.g. BUY-203", true)
                                .integer("dailyCap", "New daily cap (calls per day).", true)
                                .string("reason", REASON, true).build(), true,
                        (in, ctx) -> submit(actions, ctx, ActionType.SET_BUYER_CAP, in.get("buyerId").asText(),
                                in.get("dailyCap").asText(), in.get("reason").asText())),

                new Tool("set_buyer_status",
                        "Propose pausing (PAUSED) or resuming (ACTIVE) a buyer. Always needs human approval. Pausing a buyer "
                                + "that is the only one covering some states strands those calls; check_buyer_coverage first."
                                + GUARDRAIL_NOTE,
                        Schema.object().string("buyerId", "e.g. BUY-202", true)
                                .enumString("status", "ACTIVE or PAUSED", true, "ACTIVE", "PAUSED")
                                .string("reason", REASON, true).build(), true,
                        (in, ctx) -> submit(actions, ctx, ActionType.SET_BUYER_STATUS, in.get("buyerId").asText(),
                                in.get("status").asText(), in.get("reason").asText())),

                new Tool("flag_publisher",
                        "Flag a publisher for human review (traffic quality concerns). Applied automatically: it changes no "
                                + "routing, it puts the publisher in front of a person." + GUARDRAIL_NOTE,
                        Schema.object().string("publisherId", "e.g. PUB-104", true)
                                .string("reason", REASON, true).build(), true,
                        (in, ctx) -> submit(actions, ctx, ActionType.FLAG_PUBLISHER, in.get("publisherId").asText(),
                                "true", in.get("reason").asText())),

                new Tool("set_publisher_status",
                        "Propose pausing (PAUSED) or resuming (ACTIVE) a publisher's traffic. Always needs human approval."
                                + GUARDRAIL_NOTE,
                        Schema.object().string("publisherId", "e.g. PUB-104", true)
                                .enumString("status", "ACTIVE or PAUSED", true, "ACTIVE", "PAUSED")
                                .string("reason", REASON, true).build(), true,
                        (in, ctx) -> submit(actions, ctx, ActionType.SET_PUBLISHER_STATUS, in.get("publisherId").asText(),
                                in.get("status").asText(), in.get("reason").asText()))
        );
    }

    private static Map<String, Object> submit(ActionService actions, ToolContext ctx, ActionType type, String target,
                                              String value, String reason) {
        PlatformAction a = actions.submit(new ActionRequest(type, target, value, reason, "agent", ctx.requestId()), ctx.dryRun());
        if (ctx.actions().stream().noneMatch(x -> x.id().equals(a.id()))) {
            ctx.actions().add(a);
        }
        String what = switch (a.status()) {
            case AUTO_APPLIED -> "APPLIED automatically. The platform setting changed from " + a.oldValue() + " to " + a.newValue() + ".";
            case PENDING_APPROVAL -> "NOT applied yet. Queued for human approval as " + a.id()
                    + " (POST /actions/" + a.id() + "/approve). Tell the user it is pending.";
            case BLOCKED_BY_POLICY -> "BLOCKED by policy, nothing changed: " + a.policyNote();
            case DRY_RUN -> "DRY RUN, nothing changed. " + a.policyNote();
            default -> a.status().name();
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", what);
        out.put("action", a);
        return out;
    }

    // ---------------------------------------------------------------------------------

    static LocalDate day(JsonNode in, String field, PlatformStore store) {
        if (!in.hasNonNull(field)) {
            return store.latestDay();
        }
        try {
            return LocalDate.parse(in.get(field).asText().trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("'" + field + "' must be YYYY-MM-DD, got '" + in.get(field).asText()
                    + "'. Days with data: " + store.days());
        }
    }

    private static String[] names(Enum<?>[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i].name();
        }
        return out;
    }
}
