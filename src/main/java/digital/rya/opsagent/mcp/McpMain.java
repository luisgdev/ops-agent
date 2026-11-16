package digital.rya.opsagent.mcp;

import digital.rya.opsagent.actions.ActionPolicy;
import digital.rya.opsagent.actions.ActionService;
import digital.rya.opsagent.actions.AuditLog;
import digital.rya.opsagent.metrics.MetricsService;
import digital.rya.opsagent.platform.PlatformLoader;
import digital.rya.opsagent.platform.PlatformStore;
import digital.rya.opsagent.tools.OpsTools;
import digital.rya.opsagent.tools.ToolRegistry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;

/**
 * Entry point of the MCP server (stdio). Run it with {@code scripts/mcp.sh}.
 *
 * <p>Environment: {@code OPSAGENT_DATA_DIR} (default {@code data}), {@code OPSAGENT_AUDIT_FILE}
 * (default {@code audit/audit-log.jsonl}), {@code OPSAGENT_DRY_RUN=true} (action tools only
 * simulate), {@code OPSAGENT_AUTO_APPLY=false} (kill switch: every action needs approval).
 * stdout carries only protocol messages; anything else goes to stderr.
 */
public final class McpMain {

    private McpMain() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream protocol = new PrintStream(System.out, false, StandardCharsets.UTF_8);
        System.setOut(System.err); // a stray println must never corrupt the protocol stream

        Path dataDir = Path.of(env("OPSAGENT_DATA_DIR", "data"));
        String audit = env("OPSAGENT_AUDIT_FILE", "audit/audit-log.jsonl");
        boolean dryRun = Boolean.parseBoolean(env("OPSAGENT_DRY_RUN", "false"));
        boolean autoApply = Boolean.parseBoolean(env("OPSAGENT_AUTO_APPLY", "true"));

        PlatformStore store = PlatformLoader.load(dataDir);
        MetricsService metrics = new MetricsService(store);
        ActionPolicy policy = new ActionPolicy(store, metrics, autoApply);
        ActionService actions = new ActionService(store, policy,
                new AuditLog(audit.isBlank() ? null : Path.of(audit)), Clock.systemUTC());
        ToolRegistry tools = new ToolRegistry(OpsTools.all(metrics, actions));

        System.err.println("rya-ops-agent MCP server ready: " + tools.names().size() + " tools, data " + dataDir
                + (dryRun ? ", DRY RUN" : "") + (autoApply ? "" : ", auto-apply OFF"));
        new McpServer(tools, dryRun, Instructions.text(store, dryRun))
                .serve(new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), protocol);
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
