package digital.rya.opsagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tiny JSON Schema builder and validator: just the subset the tools use (object with typed
 * properties, required fields, enums, no extra properties). Enough to catch the usual bad
 * tool calls (missing field, wrong type, invented parameter) and tell the model exactly what
 * was wrong, so it can retry.
 */
public final class Schema {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectNode root = JSON.createObjectNode();
    private final ObjectNode props = root.putObject("properties");
    private final ArrayNode required = JSON.createArrayNode();

    private Schema() {
        root.put("type", "object");
        root.put("additionalProperties", false);
    }

    public static Schema object() {
        return new Schema();
    }

    public Schema string(String name, String description, boolean isRequired) {
        return prop(name, "string", description, isRequired);
    }

    public Schema integer(String name, String description, boolean isRequired) {
        return prop(name, "integer", description, isRequired);
    }

    public Schema enumString(String name, String description, boolean isRequired, String... values) {
        prop(name, "string", description, isRequired);
        ArrayNode e = ((ObjectNode) props.get(name)).putArray("enum");
        for (String v : values) {
            e.add(v);
        }
        return this;
    }

    private Schema prop(String name, String type, String description, boolean isRequired) {
        ObjectNode p = props.putObject(name);
        p.put("type", type);
        p.put("description", description);
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    public JsonNode build() {
        if (!required.isEmpty()) {
            root.set("required", required);
        }
        return root;
    }

    /** @return human-readable problems; empty when the input is valid */
    public static List<String> validate(JsonNode schema, JsonNode input) {
        List<String> errors = new ArrayList<>();
        if (input == null || !input.isObject()) {
            errors.add("input must be a JSON object");
            return errors;
        }
        JsonNode properties = schema.path("properties");
        for (JsonNode r : schema.path("required")) {
            JsonNode v = input.get(r.asText());
            if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
                errors.add("missing required field '" + r.asText() + "'");
            }
        }
        Iterator<Map.Entry<String, JsonNode>> it = input.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> f = it.next();
            JsonNode def = properties.get(f.getKey());
            if (def == null) {
                errors.add("unknown field '" + f.getKey() + "' (allowed: " + fieldNames(properties) + ")");
                continue;
            }
            JsonNode v = f.getValue();
            if (v.isNull()) {
                continue;
            }
            String type = def.path("type").asText();
            boolean ok = switch (type) {
                case "string" -> v.isTextual();
                case "integer" -> v.isIntegralNumber() || (v.isTextual() && v.asText().matches("-?\\d+"));
                case "number" -> v.isNumber();
                case "boolean" -> v.isBoolean();
                default -> true;
            };
            if (!ok) {
                errors.add("field '" + f.getKey() + "' must be " + type + ", got " + v);
                continue;
            }
            if (def.has("enum")) {
                boolean match = false;
                for (JsonNode e : def.get("enum")) {
                    match |= e.asText().equals(v.asText());
                }
                if (!match) {
                    errors.add("field '" + f.getKey() + "' must be one of " + def.get("enum") + ", got " + v);
                }
            }
        }
        return errors;
    }

    private static String fieldNames(JsonNode properties) {
        List<String> names = new ArrayList<>();
        properties.fieldNames().forEachRemaining(names::add);
        return String.join(", ", names);
    }
}
