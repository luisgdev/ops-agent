package digital.rya.opsagent.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.BiFunction;

/**
 * A tool the model can call. {@code inputSchema} is the JSON Schema sent to Claude; the
 * {@link ToolRegistry} validates the model's input against it before calling the handler.
 *
 * @param handler receives the validated input and the per-request context, returns any object
 *                (it is serialized to JSON for the model)
 */
public record Tool(String name, String description, JsonNode inputSchema, boolean changesPlatform,
                   BiFunction<JsonNode, ToolContext, Object> handler) {
}
