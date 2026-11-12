package digital.rya.opsagent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Holds the tools, produces their definitions for the Claude API, and runs them safely:
 * unknown tool, invalid input and handler exceptions all come back as an error result the
 * model can read and recover from. Nothing a tool does can crash the agent loop.
 */
public class ToolRegistry {

    /** Result of one tool call, ready to send back to the model as a tool_result. */
    public record Outcome(boolean ok, String content) {
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public ToolRegistry(List<Tool> tools) {
        for (Tool t : tools) {
            if (this.tools.put(t.name(), t) != null) {
                throw new IllegalArgumentException("Duplicate tool name " + t.name());
            }
        }
    }

    /** The "tools" array for the Messages API. */
    public ArrayNode definitions() {
        ArrayNode arr = json.createArrayNode();
        for (Tool t : tools.values()) {
            ObjectNode d = arr.addObject();
            d.put("name", t.name());
            d.put("description", t.description());
            d.set("input_schema", t.inputSchema());
        }
        return arr;
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public Outcome execute(String name, JsonNode input, ToolContext ctx) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return new Outcome(false, "Unknown tool '" + name + "'. Available tools: " + String.join(", ", tools.keySet()));
        }
        List<String> problems = Schema.validate(tool.inputSchema(), input);
        if (!problems.isEmpty()) {
            return new Outcome(false, "Invalid input for " + name + ": " + String.join("; ", problems)
                    + ". Fix the input and call the tool again.");
        }
        try {
            Object result = tool.handler().apply(input, ctx);
            return new Outcome(true, json.writeValueAsString(result));
        } catch (IllegalArgumentException | NoSuchElementException | IllegalStateException e) {
            return new Outcome(false, name + " failed: " + e.getMessage());
        } catch (JsonProcessingException e) {
            return new Outcome(false, name + " produced a result that could not be serialized: " + e.getOriginalMessage());
        } catch (RuntimeException e) {
            return new Outcome(false, name + " failed with an internal error (" + e.getClass().getSimpleName() + "). "
                    + "Do not retry the same call; continue with the other tools.");
        }
    }
}
