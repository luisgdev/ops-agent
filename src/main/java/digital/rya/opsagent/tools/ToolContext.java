package digital.rya.opsagent.tools;

import digital.rya.opsagent.actions.PlatformAction;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-request state shared by all tool calls of one /agent/ask request.
 *
 * @param dryRun  when true, action tools record what they would do and change nothing
 * @param actions every action submitted during this request (applied, pending, blocked, dry-run)
 */
public record ToolContext(String requestId, boolean dryRun, List<PlatformAction> actions) {

    public static ToolContext of(String requestId, boolean dryRun) {
        return new ToolContext(requestId, dryRun, new ArrayList<>());
    }
}
