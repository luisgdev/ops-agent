package digital.rya.opsagent.config;

import digital.rya.opsagent.actions.ActionService;
import digital.rya.opsagent.agent.AgentService;
import digital.rya.opsagent.metrics.MetricsService;
import digital.rya.opsagent.platform.PlatformStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Exposes the plain-Java components as Spring beans. Settings come from application.properties. */
@Configuration
public class AppConfig {

    @Bean
    public OpsAgent opsAgent(
            @Value("${opsagent.data-dir}") String dataDir,
            @Value("${opsagent.audit-file:}") String auditFile,
            @Value("${opsagent.auto-apply-enabled:true}") boolean autoApply,
            @Value("${anthropic.api-key:}") String apiKey,
            @Value("${anthropic.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${anthropic.model}") String model,
            @Value("${anthropic.max-tokens:4096}") int maxTokens,
            @Value("${opsagent.max-iterations:12}") int maxIterations,
            @Value("${opsagent.max-tool-calls:30}") int maxToolCalls,
            @Value("${anthropic.timeout-seconds:120}") int timeoutSeconds) {
        return OpsAgent.create(new OpsAgent.Settings(Path.of(dataDir), auditFile.isBlank() ? null : Path.of(auditFile),
                autoApply, apiKey, baseUrl, model, maxTokens, maxIterations, maxToolCalls, timeoutSeconds));
    }

    @Bean
    public AgentService agentService(OpsAgent ops) {
        return ops.agent;
    }

    @Bean
    public ActionService actionService(OpsAgent ops) {
        return ops.actions;
    }

    @Bean
    public MetricsService metricsService(OpsAgent ops) {
        return ops.metrics;
    }

    @Bean
    public PlatformStore platformStore(OpsAgent ops) {
        return ops.store;
    }
}
