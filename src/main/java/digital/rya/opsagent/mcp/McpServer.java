package digital.rya.opsagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import digital.rya.opsagent.tools.Tool;
import digital.rya.opsagent.tools.ToolContext;
import digital.rya.opsagent.tools.ToolRegistry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.UUID;

/**
 * A minimal Model Context Protocol server (stdio transport, newline-delimited JSON-RPC 2.0).
 * It exposes the same tools the built-in agent uses, so any MCP client (Claude Code, Claude
 * Desktop, IntelliJ, ...) can investigate the platform with its own model. No API key needed.
 *
 * <p>Every action tool still goes through {@code ActionService}: the policy, audit log and dry
 * run apply exactly as for the REST API. Approving or rejecting pending actions is deliberately
 * <em>not</em> exposed here, so a model can never approve its own proposals.
 *
 * <p>Plain Java on purpose (like the agent loop): the protocol subset we need is small.
 */
public final class McpServer {

    static final String LATEST_PROTOCOL = "2025-06-18";
    private static final List<String> SUPPORTED = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    private final ToolRegistry tools;
    private final boolean dryRun;
    private final String instructions;
    private final ObjectMapper json = new ObjectMapper();

    public McpServer(ToolRegistry tools, boolean dryRun, String instructions) {
        this.tools = tools;
        this.dryRun = dryRun;
        this.instructions = instructions;
    }

    /** Reads requests line by line until the client closes the input. */
    public void serve(BufferedReader in, PrintStream out) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String reply = handleLine(line);
            if (reply != null) {
                out.println(reply);
                out.flush();
            }
        }
    }

    /** Handles one JSON-RPC message; returns the response line, or null for notifications. */
    public String handleLine(String line) {
        JsonNode msg;
        try {
            msg = json.readTree(line);
        } catch (IOException e) {
            return error(null, -32700, "Parse error");
        }
        if (msg == null || !msg.isObject()) {
            return error(null, -32600, "Invalid request");
        }
        JsonNode id = msg.get("id");
        String method = msg.path("method").asText("");
        if (id == null || id.isNull()) {
            return null; // notification (e.g. notifications/initialized): never answered
        }
        if (method.isEmpty()) {
            return null; // a response from the client to a request we never send
        }
        try {
            return switch (method) {
                case "initialize" -> result(id, initialize(msg.path("params")));
                case "ping" -> result(id, json.createObjectNode());
                case "tools/list" -> result(id, listTools());
                case "tools/call" -> result(id, callTool(msg.path("params")));
                default -> error(id, -32601, "Method not found: " + method);
            };
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (RuntimeException e) {
            return error(id, -32603, "Internal error");
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String asked = params.path("protocolVersion").asText("");
        ObjectNode r = json.createObjectNode();
        r.put("protocolVersion", SUPPORTED.contains(asked) ? asked : LATEST_PROTOCOL);
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", "rya-ops-agent");
        info.put("version", "0.1.0");
        r.put("instructions", instructions);
        return r;
    }

    private ObjectNode listTools() {
        ObjectNode r = json.createObjectNode();
        ArrayNode arr = r.putArray("tools");
        for (Tool t : tools.all()) {
            ObjectNode d = arr.addObject();
            d.put("name", t.name());
            d.put("description", t.description());
            d.set("inputSchema", t.inputSchema());
            ObjectNode ann = d.putObject("annotations");
            ann.put("readOnlyHint", !t.changesPlatform());
            if (t.changesPlatform()) {
                ann.put("destructiveHint", false);
            }
        }
        return r;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Missing tool name");
        }
        JsonNode args = params.get("arguments");
        if (args == null || args.isNull()) {
            args = json.createObjectNode();
        }
        // Unknown tools and bad input come back as tool errors the model can read and fix.
        ToolRegistry.Outcome o = tools.execute(name, args, ToolContext.of("mcp-" + UUID.randomUUID(), dryRun));
        ObjectNode r = json.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", o.content());
        r.put("isError", !o.ok());
        return r;
    }

    private String result(JsonNode id, JsonNode result) {
        ObjectNode n = json.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id);
        n.set("result", result);
        return n.toString();
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode n = json.createObjectNode();
        n.put("jsonrpc", "2.0");
        if (id == null) {
            n.putNull("id");
        } else {
            n.set("id", id);
        }
        n.putObject("error").put("code", code).put("message", message);
        return n.toString();
    }
}
