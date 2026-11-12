package digital.rya.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import digital.rya.opsagent.agent.AgentResponse.ToolCallRecord;
import digital.rya.opsagent.agent.AgentResponse.Usage;
import digital.rya.opsagent.tools.ToolContext;
import digital.rya.opsagent.tools.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The agent loop (Claude tool use):
 *
 * <pre>
 *   send question + tools
 *   loop (at most maxIterations model calls):
 *     if Claude asks for tools -> run them, send the results back
 *     else                     -> that text is the answer, stop
 *   on the last allowed call, tools are disabled (tool_choice none) so Claude must answer
 * </pre>
 *
 * Bad tool calls never break the loop: they come back to Claude as is_error results.
 * A hard cap on tool calls protects against a model that keeps calling tools in parallel.
 */
public class AgentService {

    /** @param dataContext one line telling the model which days of data exist (avoids guessing dates) */
    public record Config(String model, int maxTokens, int maxIterations, int maxToolCalls, String dataContext) {
        public Config {
            if (maxIterations < 2) {
                throw new IllegalArgumentException("maxIterations must be at least 2");
            }
        }
    }

    private final MessagesApi api;
    private final ToolRegistry tools;
    private final Config config;
    private final ObjectMapper json = new ObjectMapper();

    public AgentService(MessagesApi api, ToolRegistry tools, Config config) {
        this.api = api;
        this.tools = tools;
        this.config = config;
    }

    public AgentResponse ask(String question, boolean dryRun) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        String requestId = "REQ-" + UUID.randomUUID().toString().substring(0, 8);
        ToolContext ctx = ToolContext.of(requestId, dryRun);
        List<ToolCallRecord> calls = new ArrayList<>();
        long[] usage = new long[4];

        ArrayNode messages = json.createArrayNode();
        messages.addObject().put("role", "user").put("content", question);

        int iteration = 0;
        int toolCallCount = 0;
        try {
            while (iteration < config.maxIterations()) {
                iteration++;
                boolean lastCall = iteration == config.maxIterations();
                JsonNode resp = api.createMessage(request(messages, dryRun, lastCall));
                addUsage(usage, resp.path("usage"));

                JsonNode content = resp.path("content");
                messages.addObject().put("role", "assistant").set("content", content);
                String stop = resp.path("stop_reason").asText();

                List<JsonNode> toolUses = new ArrayList<>();
                content.forEach(b -> {
                    if ("tool_use".equals(b.path("type").asText())) {
                        toolUses.add(b);
                    }
                });

                if (!"tool_use".equals(stop) || toolUses.isEmpty()) {
                    String answer = text(content);
                    // On the last call tools were disabled, so the answer was forced by the limit.
                    String reason = lastCall ? "max_iterations" : stop;
                    if ("max_tokens".equals(stop)) {
                        answer += "\n\n(Answer cut off: max_tokens reached.)";
                    }
                    return done(requestId, question, dryRun, answer, reason, iteration, calls, ctx, usage);
                }

                // Run every tool Claude asked for and send all results back in one user message.
                ArrayNode results = json.createArrayNode();
                for (JsonNode tu : toolUses) {
                    String name = tu.path("name").asText();
                    JsonNode input = tu.path("input");
                    long t0 = System.currentTimeMillis();
                    ToolRegistry.Outcome out;
                    if (toolCallCount >= config.maxToolCalls()) {
                        out = new ToolRegistry.Outcome(false, "Tool-call budget for this request is used up ("
                                + config.maxToolCalls() + "). Answer with the information you already have.");
                    } else {
                        toolCallCount++;
                        out = tools.execute(name, input, ctx);
                    }
                    calls.add(new ToolCallRecord(iteration, name, input, out.ok(), out.ok() ? null : out.content(),
                            preview(out.content()), System.currentTimeMillis() - t0));
                    ObjectNode r = results.addObject();
                    r.put("type", "tool_result");
                    r.put("tool_use_id", tu.path("id").asText());
                    r.put("content", out.content());
                    if (!out.ok()) {
                        r.put("is_error", true);
                    }
                }
                if (iteration + 1 == config.maxIterations()) {
                    results.addObject().put("type", "text").put("text",
                            "Step limit reached: no more tools are available. Write your final answer now with what you have, "
                                    + "and say what you could not check.");
                }
                messages.addObject().put("role", "user").set("content", results);
            }
            // Only reachable if the last call still returned tool_use despite tool_choice none.
            return done(requestId, question, dryRun, "Stopped after " + config.maxIterations()
                    + " steps without a final answer. See toolCalls and actions for what was done.", "max_iterations",
                    iteration, calls, ctx, usage);
        } catch (MessagesApi.ClaudeApiException e) {
            // Still return the actions: some may already have been applied before the failure.
            return done(requestId, question, dryRun, "The agent stopped because the Claude API failed: " + e.getMessage()
                    + (ctx.actions().isEmpty() ? "" : " Actions submitted before the failure are listed in 'actions'."),
                    "error", iteration, calls, ctx, usage);
        }
    }

    private ObjectNode request(ArrayNode messages, boolean dryRun, boolean lastCall) {
        ObjectNode req = json.createObjectNode();
        req.put("model", config.model());
        req.put("max_tokens", config.maxTokens());
        req.put("system", SystemPrompt.build(dryRun, config.dataContext()));
        ArrayNode defs = tools.definitions();
        // Cache the (long, unchanging) tool definitions + system prompt across loop iterations.
        if (!defs.isEmpty()) {
            ((ObjectNode) defs.get(defs.size() - 1)).putObject("cache_control").put("type", "ephemeral");
        }
        req.set("tools", defs);
        if (lastCall) {
            req.putObject("tool_choice").put("type", "none");
        }
        req.set("messages", messages);
        return req;
    }

    private AgentResponse done(String requestId, String question, boolean dryRun, String answer, String stopReason,
                               int iterations, List<ToolCallRecord> calls, ToolContext ctx, long[] u) {
        return new AgentResponse(requestId, question, dryRun, config.model(), answer, stopReason, iterations,
                List.copyOf(calls), List.copyOf(ctx.actions()), new Usage(u[0], u[1], u[2], u[3]));
    }

    private static String text(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        content.forEach(b -> {
            if ("text".equals(b.path("type").asText())) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(b.path("text").asText());
            }
        });
        return sb.toString().trim();
    }

    private static void addUsage(long[] u, JsonNode usage) {
        u[0] += usage.path("input_tokens").asLong();
        u[1] += usage.path("output_tokens").asLong();
        u[2] += usage.path("cache_read_input_tokens").asLong();
        u[3] += usage.path("cache_creation_input_tokens").asLong();
    }

    private static String preview(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
