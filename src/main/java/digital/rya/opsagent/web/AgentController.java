package digital.rya.opsagent.web;

import digital.rya.opsagent.agent.AgentResponse;
import digital.rya.opsagent.agent.AgentService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentController {

    /** @param dryRun optional; true = action tools simulate only */
    public record AskRequest(String question, Boolean dryRun) {
    }

    private final AgentService agent;

    public AgentController(AgentService agent) {
        this.agent = agent;
    }

    /** Ask the agent a question in plain English. Returns the answer, tool calls and actions. */
    @PostMapping("/agent/ask")
    public AgentResponse ask(@RequestBody AskRequest req) {
        return agent.ask(req.question(), Boolean.TRUE.equals(req.dryRun()));
    }
}
