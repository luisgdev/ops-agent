package digital.rya.opsagent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import digital.rya.opsagent.agent.AgentResponse;
import digital.rya.opsagent.config.OpsAgent;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ask one question without starting the web server (handy for development and for
 * reproducing the README answer). Usage:
 *
 * <pre>
 *   ANTHROPIC_API_KEY=... java -cp ... digital.rya.opsagent.cli.AskCli [--dry-run] [--out file.json] "question"
 * </pre>
 */
public final class AskCli {

    private AskCli() {
    }

    public static void main(String[] args) throws Exception {
        boolean dryRun = false;
        String out = null;
        String question = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run" -> dryRun = true;
                case "--out" -> out = args[++i];
                default -> question = args[i];
            }
        }
        if (question == null) {
            System.err.println("Usage: AskCli [--dry-run] [--out file.json] \"question\"");
            System.exit(2);
        }
        String env = System.getenv("ANTHROPIC_MODEL");
        OpsAgent ops = OpsAgent.create(new OpsAgent.Settings(
                Path.of(System.getProperty("opsagent.data-dir", "data")), null, true,
                System.getenv("ANTHROPIC_API_KEY"), "https://api.anthropic.com",
                env == null || env.isBlank() ? "claude-sonnet-5-5" : env, 4096, 12, 30, 180));

        AgentResponse resp = ops.agent.ask(question, dryRun);
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);
        String body = json.writeValueAsString(resp);
        if (out != null) {
            Files.writeString(Path.of(out), body);
        }
        System.out.println(resp.answer());
        System.out.println("\n--- " + resp.toolCalls().size() + " tool calls, " + resp.actions().size() + " actions, stop="
                + resp.stopReason() + ", tokens in/out=" + resp.usage().inputTokens() + "/" + resp.usage().outputTokens());
    }
}
