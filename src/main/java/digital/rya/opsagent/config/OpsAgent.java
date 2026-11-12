package digital.rya.opsagent.config;

import digital.rya.opsagent.actions.ActionPolicy;
import digital.rya.opsagent.actions.ActionService;
import digital.rya.opsagent.actions.AuditLog;
import digital.rya.opsagent.agent.AgentService;
import digital.rya.opsagent.agent.AnthropicMessagesApi;
import digital.rya.opsagent.agent.MessagesApi;
import digital.rya.opsagent.metrics.MetricsService;
import digital.rya.opsagent.platform.PlatformLoader;
import digital.rya.opsagent.platform.PlatformStore;
import digital.rya.opsagent.tools.OpsTools;
import digital.rya.opsagent.tools.ToolRegistry;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * Wires the whole agent together in plain Java (no framework). Spring ({@link AppConfig})
 * and the command-line runner both use this, so there is exactly one place where the
 * pieces are connected.
 */
public final class OpsAgent {

    public record Settings(
            Path dataDir,
            Path auditFile,
            boolean autoApplyEnabled,
            String apiKey,
            String apiBaseUrl,
            String model,
            int maxTokens,
            int maxIterations,
            int maxToolCalls,
            int timeoutSeconds) {
    }

    public final PlatformStore store;
    public final MetricsService metrics;
    public final ActionService actions;
    public final ToolRegistry tools;
    public final AgentService agent;

    private OpsAgent(PlatformStore store, MetricsService metrics, ActionService actions, ToolRegistry tools, AgentService agent) {
        this.store = store;
        this.metrics = metrics;
        this.actions = actions;
        this.tools = tools;
        this.agent = agent;
    }

    static String dataContext(PlatformStore store) {
        return "Call data available for: " + store.days() + " (US Central time). The latest day is " + store.latestDay() + ".";
    }

    public static OpsAgent create(Settings s) {
        MessagesApi api = new AnthropicMessagesApi(s.apiBaseUrl(), s.apiKey(), Duration.ofSeconds(s.timeoutSeconds()));
        return create(s, api, Clock.systemUTC());
    }

    /** Same wiring with an injected API client and clock (used by tests). */
    public static OpsAgent create(Settings s, MessagesApi api, Clock clock) {
        PlatformStore store = PlatformLoader.load(s.dataDir());
        MetricsService metrics = new MetricsService(store);
        ActionPolicy policy = new ActionPolicy(store, metrics, s.autoApplyEnabled());
        ActionService actions = new ActionService(store, policy, new AuditLog(s.auditFile()), clock);
        ToolRegistry tools = new ToolRegistry(OpsTools.all(metrics, actions));
        AgentService agent = new AgentService(api, tools,
                new AgentService.Config(s.model(), s.maxTokens(), s.maxIterations(), s.maxToolCalls(), dataContext(store)));
        return new OpsAgent(store, metrics, actions, tools, agent);
    }
}
