package digital.rya.opsagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import digital.rya.opsagent.TestData;
import digital.rya.opsagent.TestData.Fixture;
import digital.rya.opsagent.actions.ActionStatus;
import digital.rya.opsagent.tools.OpsTools;
import digital.rya.opsagent.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Fixture fx = TestData.fixture(true);

    private McpServer server(boolean dryRun) {
        return new McpServer(new ToolRegistry(OpsTools.all(fx.metrics(), fx.actions())), dryRun, "test instructions");
    }

    private JsonNode send(McpServer s, String line) throws Exception {
        String reply = s.handleLine(line);
        return reply == null ? null : JSON.readTree(reply);
    }

    @Test
    void initializeNegotiatesVersionAndReturnsInstructions() throws Exception {
        JsonNode r = send(server(false), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                + "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}")
                .get("result");
        assertEquals("2024-11-05", r.get("protocolVersion").asText());
        assertTrue(r.get("capabilities").has("tools"));
        assertEquals("test instructions", r.get("instructions").asText());
        // an unknown version falls back to the latest we support
        JsonNode r2 = send(server(false), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}")
                .get("result");
        assertEquals(McpServer.LATEST_PROTOCOL, r2.get("protocolVersion").asText());
    }

    @Test
    void notificationsGetNoReply() throws Exception {
        assertNull(send(server(false), "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
    }

    @Test
    void listsAllToolsWithReadOnlyHints() throws Exception {
        JsonNode tools = send(server(false), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}").get("result").get("tools");
        assertEquals(15, tools.size());
        boolean sawRead = false;
        boolean sawAction = false;
        for (JsonNode t : tools) {
            assertTrue(t.has("inputSchema"));
            assertFalse(t.get("name").asText().contains("approve"), "approval must not be exposed");
            boolean readOnly = t.get("annotations").get("readOnlyHint").asBoolean();
            sawRead |= readOnly;
            sawAction |= !readOnly;
        }
        assertTrue(sawRead && sawAction);
    }

    @Test
    void readToolCallReturnsJsonText() throws Exception {
        JsonNode r = send(server(false), "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"explain_margin_change\",\"arguments\":{\"baselineDay\":\"2026-11-02\",\"targetDay\":\"2026-11-04\"}}}")
                .get("result");
        assertFalse(r.get("isError").asBoolean(), r.toString());
        assertEquals("text", r.get("content").get(0).get("type").asText());
        assertTrue(JSON.readTree(r.get("content").get(0).get("text").asText()).isObject());
    }

    @Test
    void badToolInputIsAToolErrorNotAProtocolError() throws Exception {
        JsonNode reply = send(server(false), "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}");
        assertFalse(reply.has("error"));
        assertTrue(reply.get("result").get("isError").asBoolean());
        assertTrue(reply.get("result").get("content").get(0).get("text").asText().contains("Unknown tool"));
    }

    @Test
    void protocolErrors() throws Exception {
        assertEquals(-32700, send(server(false), "{not json").get("error").get("code").asInt());
        assertEquals(-32601, send(server(false), "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}").get("error").get("code").asInt());
        assertEquals(-32602, send(server(false), "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{}}").get("error").get("code").asInt());
    }

    @Test
    void dryRunModeSimulatesActions() throws Exception {
        String call = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"flag_publisher\","
                + "\"arguments\":{\"publisherId\":\"PUB-104\",\"reason\":\"22 duplicates and 52 calls under 30s on Nov 4\"}}}";
        JsonNode r = send(server(true), call).get("result");
        assertFalse(r.get("isError").asBoolean(), r.toString());
        assertEquals(ActionStatus.DRY_RUN, fx.actions().list(null).get(0).status());
    }
}
