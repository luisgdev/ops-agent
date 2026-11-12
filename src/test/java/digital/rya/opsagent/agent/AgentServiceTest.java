package digital.rya.opsagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import digital.rya.opsagent.TestData;
import digital.rya.opsagent.TestData.Fixture;
import digital.rya.opsagent.actions.ActionStatus;
import digital.rya.opsagent.tools.OpsTools;
import digital.rya.opsagent.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The agent loop, driven by a scripted fake Claude: no network, no API cost, fully deterministic. */
class AgentServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Returns canned responses in order and remembers every request it received. */
    static final class ScriptedApi implements MessagesApi {
        final Deque<JsonNode> responses = new ArrayDeque<>();
        final List<ObjectNode> requests = new ArrayList<>();
        RuntimeException failOnCall;
        int failAt = -1;

        ScriptedApi then(JsonNode response) {
            responses.add(response);
            return this;
        }

        @Override
        public JsonNode createMessage(ObjectNode request) {
            requests.add(request.deepCopy());
            if (requests.size() == failAt) {
                throw failOnCall;
            }
            if (responses.isEmpty()) {
                throw new AssertionError("Agent made more model calls than scripted");
            }
            return responses.poll();
        }
    }

    static JsonNode toolUse(String... nameIdAndInputJson) {
        ObjectNode resp = JSON.createObjectNode();
        ArrayNode content = resp.putArray("content");
        for (int i = 0; i < nameIdAndInputJson.length; i += 3) {
            ObjectNode b = content.addObject();
            b.put("type", "tool_use");
            b.put("name", nameIdAndInputJson[i]);
            b.put("id", nameIdAndInputJson[i + 1]);
            try {
                b.set("input", JSON.readTree(nameIdAndInputJson[i + 2]));
            } catch (Exception e) {
                throw new IllegalArgumentException(e);
            }
        }
        resp.put("stop_reason", "tool_use");
        resp.putObject("usage").put("input_tokens", 100).put("output_tokens", 20);
        return resp;
    }

    static JsonNode text(String answer) {
        ObjectNode resp = JSON.createObjectNode();
        resp.putArray("content").addObject().put("type", "text").put("text", answer);
        resp.put("stop_reason", "end_turn");
        resp.putObject("usage").put("input_tokens", 100).put("output_tokens", 50);
        return resp;
    }

    private final Fixture f = TestData.fixture(true);

    private AgentService agent(ScriptedApi api, int maxIterations) {
        ToolRegistry tools = new ToolRegistry(OpsTools.all(f.metrics(), f.actions()));
        return new AgentService(api, tools, new AgentService.Config("test-model", 1024, maxIterations, 30, "Call data available for: [2026-11-02, 2026-11-03, 2026-11-04]"));
    }

    /** The tool_result blocks the agent sent back in its n-th request (1-based). */
    private static JsonNode lastToolResults(ScriptedApi api, int request) {
        ArrayNode msgs = (ArrayNode) api.requests.get(request - 1).get("messages");
        return msgs.get(msgs.size() - 1).get("content");
    }

    @Test
    void runsToolsSendsResultsBackAndReturnsTheAnswer() {
        ScriptedApi api = new ScriptedApi()
                .then(toolUse("explain_margin_change", "t1", "{\"baselineDay\":\"2026-11-02\",\"targetDay\":\"2026-11-04\"}"))
                .then(text("Buyer South stopped answering."));
        AgentResponse r = agent(api, 6).ask("Why did margin drop?", false);

        assertEquals("Buyer South stopped answering.", r.answer());
        assertEquals("end_turn", r.stopReason());
        assertEquals(2, r.iterations());
        assertEquals(1, r.toolCalls().size());
        assertTrue(r.toolCalls().get(0).ok());

        JsonNode result = lastToolResults(api, 2).get(0);
        assertEquals("tool_result", result.get("type").asText());
        assertEquals("t1", result.get("tool_use_id").asText());
        assertTrue(result.get("content").asText().contains("BUYER_NOT_ANSWERING"), "real computed numbers go to the model");
        assertEquals(200, r.usage().inputTokens());
        // tools are offered to the model on every call
        assertEquals(api.requests.get(0).get("tools").size(), new ToolRegistry(OpsTools.all(f.metrics(), f.actions())).names().size());
    }

    @Test
    void unknownToolAndBadInputComeBackAsErrorsAndTheLoopContinues() {
        ScriptedApi api = new ScriptedApi()
                .then(toolUse("delete_everything", "t1", "{}",
                        "set_buyer_cap", "t2", "{\"buyerId\":\"BUY-203\",\"dailyCap\":\"lots\",\"reason\":\"x\"}",
                        "get_day_summary", "t3", "{\"day\":\"Nov 4\"}"))
                .then(text("Done."));
        AgentResponse r = agent(api, 6).ask("Do something", false);

        assertEquals("Done.", r.answer());
        assertEquals(3, r.toolCalls().size());
        r.toolCalls().forEach(c -> assertFalse(c.ok()));
        JsonNode results = lastToolResults(api, 2);
        assertTrue(results.get(0).get("is_error").asBoolean());
        assertTrue(results.get(0).get("content").asText().contains("Unknown tool"));
        assertTrue(results.get(1).get("content").asText().contains("dailyCap"));
        assertTrue(results.get(2).get("content").asText().contains("YYYY-MM-DD"));
        assertEquals(75, f.store().buyer("BUY-203").dailyCap(), "a bad call changes nothing");
    }

    @Test
    void stopsAtTheIterationLimitAndForcesAFinalAnswer() {
        ScriptedApi api = new ScriptedApi()
                .then(toolUse("get_day_summary", "t1", "{}"))
                .then(toolUse("get_day_summary", "t2", "{}"))
                .then(text("Partial answer."));
        AgentResponse r = agent(api, 3).ask("Loop forever", false);

        assertEquals("max_iterations", r.stopReason());
        assertEquals(3, api.requests.size(), "never more model calls than the limit");
        assertEquals("none", api.requests.get(2).get("tool_choice").get("type").asText(), "last call has tools disabled");
        JsonNode lastUser = lastToolResults(api, 3);
        assertTrue(lastUser.get(lastUser.size() - 1).get("text").asText().contains("Step limit reached"));
    }

    @Test
    void actionsAreReportedWithTheirRealStatus() {
        ScriptedApi api = new ScriptedApi()
                .then(toolUse(
                        "set_buyer_priority", "t1", "{\"buyerId\":\"BUY-202\",\"priority\":2,\"reason\":\"42.9% failure rate on Nov 4 while pinging free agents\"}",
                        "set_buyer_cap", "t2", "{\"buyerId\":\"BUY-203\",\"dailyCap\":100,\"reason\":\"Cap 75 reached at 13:53, 12 extra calls rejected\"}"))
                .then(text("Demoted Buyer South; cap increase for Buyer East waits for approval."));
        AgentResponse r = agent(api, 6).ask("Fix it", false);

        assertEquals(2, r.actions().size());
        assertEquals(ActionStatus.AUTO_APPLIED, r.actions().get(0).status());
        assertEquals(ActionStatus.PENDING_APPROVAL, r.actions().get(1).status());
        assertEquals(2, f.store().buyer("BUY-202").priority());
        assertEquals(75, f.store().buyer("BUY-203").dailyCap());
    }

    @Test
    void dryRunRequestChangesNothing() {
        ScriptedApi api = new ScriptedApi()
                .then(toolUse("set_buyer_priority", "t1", "{\"buyerId\":\"BUY-202\",\"priority\":2,\"reason\":\"42.9% failure rate on Nov 4 while pinging free agents\"}"))
                .then(text("Would demote Buyer South."));
        AgentResponse r = agent(api, 6).ask("Fix it", true);

        assertTrue(r.dryRun());
        assertEquals(ActionStatus.DRY_RUN, r.actions().get(0).status());
        assertEquals(1, f.store().buyer("BUY-202").priority());
        assertTrue(api.requests.get(0).get("system").asText().contains("DRY RUN"));
    }

    @Test
    void apiFailureStillReportsActionsAlreadyApplied() {
        ScriptedApi api = new ScriptedApi()
                .then(toolUse("flag_publisher", "t1", "{\"publisherId\":\"PUB-104\",\"reason\":\"22 duplicates and 52 calls under 30s on Nov 4\"}"));
        api.failAt = 2;
        api.failOnCall = new MessagesApi.ClaudeApiException("HTTP 529 overloaded", null);
        AgentResponse r = agent(api, 6).ask("Check publishers", false);

        assertEquals("error", r.stopReason());
        assertTrue(r.answer().contains("529"));
        assertEquals(1, r.actions().size());
        assertEquals(ActionStatus.AUTO_APPLIED, r.actions().get(0).status());
    }
}
