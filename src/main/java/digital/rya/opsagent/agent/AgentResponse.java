package digital.rya.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import digital.rya.opsagent.actions.PlatformAction;

import java.util.List;

/**
 * What POST /agent/ask returns.
 *
 * @param stopReason   end_turn (normal), max_iterations (loop limit hit), max_tokens, error
 * @param toolCalls    every tool the model called, in order, with the input and whether it worked
 * @param actions      every action submitted in this request, with its final status
 *                     (AUTO_APPLIED, PENDING_APPROVAL, BLOCKED_BY_POLICY, DRY_RUN)
 */
public record AgentResponse(
        String requestId,
        String question,
        boolean dryRun,
        String model,
        String answer,
        String stopReason,
        int iterations,
        List<ToolCallRecord> toolCalls,
        List<PlatformAction> actions,
        Usage usage) {

    public record ToolCallRecord(int iteration, String tool, JsonNode input, boolean ok, String error, String resultPreview,
                                 long durationMs) {
    }

    public record Usage(long inputTokens, long outputTokens, long cacheReadInputTokens, long cacheCreationInputTokens) {
    }
}
