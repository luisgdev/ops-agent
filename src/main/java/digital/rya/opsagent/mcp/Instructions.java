package digital.rya.opsagent.mcp;

import digital.rya.opsagent.platform.PlatformStore;

/** The guidance sent to the MCP client when it connects (the client's model reads it). */
final class Instructions {

    private Instructions() {
    }

    static String text(PlatformStore store, boolean dryRun) {
        return """
                Tools for Rya's call-routing platform (inbound Medicare / Final Expense calls routed from \
                publishers to buyers; margin = revenue - payout).
                - Investigate with the read tools first (for "why did money go down": compare_days, then \
                explain_margin_change, then get_buyer_performance / get_buyer_hourly / get_publisher_quality / \
                get_unrouted_calls).
                - Quote every number from a tool result; do not do the arithmetic yourself.
                - Before changing a buyer's priority or status, call check_buyer_coverage. Prefer the smallest \
                reversible change; lowering priority beats pausing a buyer that is the only one for some states.
                - Action tools go through a policy: each result says APPLIED, PENDING APPROVAL (with an id), BLOCKED \
                or DRY RUN. Report that status faithfully. Approvals are done by a person outside this server.
                - Give every action a reason that cites the tool numbers behind it.
                """ + "Call data available for: " + store.days() + " (US Central time). The latest day is "
                + store.latestDay() + "." + (dryRun ? "\nThis server is in DRY RUN mode: action tools only simulate." : "");
    }
}
