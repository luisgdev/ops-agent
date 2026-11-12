package digital.rya.opsagent.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import digital.rya.opsagent.actions.ActionStatus;
import digital.rya.opsagent.actions.PlatformAction;
import digital.rya.opsagent.agent.AgentResponse;
import digital.rya.opsagent.agent.AgentResponse.ToolCallRecord;
import digital.rya.opsagent.config.OpsAgent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ask one question from the terminal, without starting the web server, and print a readable
 * report (the model's Markdown is rendered here; the service itself is untouched). Usage:
 *
 * <pre>
 *   scripts/ask.sh [--dry-run] [--raw] [--no-color] [--verbose] [--out file.json] "question"
 * </pre>
 *
 * <ul>
 *   <li>{@code --dry-run}: the agent records what it would do and changes nothing</li>
 *   <li>{@code --raw}: print the model's Markdown as is</li>
 *   <li>{@code --no-color}: plain text (also automatic when piped, or if NO_COLOR is set)</li>
 *   <li>{@code --verbose}: also list every tool call</li>
 *   <li>{@code --out}: save the full JSON response</li>
 * </ul>
 */
public final class AskCli {

    private AskCli() {
    }

    public static void main(String[] args) throws Exception {
        boolean dryRun = false;
        boolean raw = false;
        boolean verbose = false;
        boolean noColor = false;
        String out = null;
        String question = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--dry-run" -> dryRun = true;
                case "--raw" -> raw = true;
                case "--verbose" -> verbose = true;
                case "--no-color" -> noColor = true;
                case "--out" -> {
                    if (i + 1 >= args.length) {
                        usage();
                    }
                    out = args[++i];
                }
                case "-h", "--help" -> usage();
                default -> question = args[i];
            }
        }
        if (question == null || question.isBlank()) {
            usage();
        }
        String key = System.getenv("ANTHROPIC_API_KEY");
        if (key == null || key.isBlank()) {
            System.err.println("ANTHROPIC_API_KEY is not set. Export it first: export ANTHROPIC_API_KEY=sk-ant-...");
            System.exit(2);
        }
        boolean color = !noColor && System.console() != null && System.getenv("NO_COLOR") == null;
        MarkdownRenderer md = new MarkdownRenderer(color, terminalWidth());
        Ansi a = new Ansi(color);

        String env = System.getenv("ANTHROPIC_MODEL");
        String model = env == null || env.isBlank() ? "claude-sonnet-5-5" : env;
        OpsAgent ops = OpsAgent.create(new OpsAgent.Settings(
                Path.of(System.getProperty("opsagent.data-dir", "data")), Path.of("audit/audit-log.jsonl"), true,
                key, "https://api.anthropic.com", model, 4096, 12, 30, 180));

        System.out.println();
        System.out.println(a.bold("Rya Ops Agent") + a.dim("  ·  " + model + (dryRun ? "  ·  DRY RUN (nothing is changed)" : "")));
        System.out.println(a.dim("Question: ") + question);
        System.out.println(a.dim("Investigating... this can take a minute."));

        long t0 = System.currentTimeMillis();
        AgentResponse resp = ops.agent.ask(question, dryRun);
        long secs = Math.round((System.currentTimeMillis() - t0) / 1000.0);

        if (out != null) {
            ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);
            Files.writeString(Path.of(out), json.writeValueAsString(resp));
        }

        if (raw) {
            System.out.println();
            System.out.println(resp.answer());
        } else {
            System.out.println();
            System.out.println(a.section("ANSWER"));
            System.out.print(md.render(resp.answer()));
        }

        if (verbose) {
            System.out.println();
            System.out.println(a.section("TOOL CALLS"));
            for (ToolCallRecord t : resp.toolCalls()) {
                System.out.println("  " + (t.ok() ? a.green("✓") : a.red("✗")) + " " + t.tool()
                        + a.dim("  " + t.input() + "  (" + t.durationMs() + " ms)")
                        + (t.ok() ? "" : "  " + a.red(String.valueOf(t.error()))));
            }
        }

        printActions(resp.actions(), a, md);

        System.out.println();
        System.out.println(a.dim("─".repeat(Math.min(terminalWidth(), 80))));
        System.out.println(a.dim(resp.toolCalls().size() + " tool calls · " + resp.iterations() + " model calls · "
                + resp.usage().inputTokens() + " tokens in / " + resp.usage().outputTokens() + " out · " + secs + "s · "
                + resp.requestId()));
        if (!"end_turn".equals(resp.stopReason())) {
            System.out.println(a.yellow("Note: the agent stopped with '" + resp.stopReason() + "'; the answer may be incomplete."));
        }
        if (resp.actions().stream().anyMatch(x -> x.status() == ActionStatus.PENDING_APPROVAL)) {
            System.out.println(a.dim("Approve or reject pending actions through the API: POST /actions/{id}/approve | /reject"
                    + " (start the server with mvn spring-boot:run)."));
        }
    }

    private static void printActions(List<PlatformAction> actions, Ansi a, MarkdownRenderer md) {
        System.out.println();
        System.out.println(a.section("ACTIONS"));
        if (actions.isEmpty()) {
            System.out.println("  " + a.dim("The agent proposed no changes."));
            return;
        }
        for (PlatformAction x : actions) {
            System.out.println("  " + statusBadge(x.status(), a) + " " + a.bold(x.id()) + "  "
                    + describe(x));
            System.out.println("      " + a.dim("Why: ") + x.reason());
            if (x.policyNote() != null && !x.policyNote().isBlank()) {
                System.out.println("      " + a.dim("Policy: ") + x.policyNote());
            }
            for (String w : x.warnings()) {
                System.out.println("      " + a.yellow("⚠ " + w));
            }
        }
    }

    private static String describe(PlatformAction x) {
        String change = x.oldValue() + " → " + x.newValue();
        return switch (x.type()) {
            case FLAG_PUBLISHER -> "Flag publisher " + x.targetId() + " for review";
            case SET_BUYER_PRIORITY -> "Buyer " + x.targetId() + " priority " + change;
            case SET_BUYER_CAP -> "Buyer " + x.targetId() + " daily cap " + change;
            case SET_BUYER_STATUS -> "Buyer " + x.targetId() + " status " + change;
            case SET_PUBLISHER_STATUS -> "Publisher " + x.targetId() + " status " + change;
        };
    }

    private static String statusBadge(ActionStatus s, Ansi a) {
        List<String> label = new ArrayList<>();
        label.add(switch (s) {
            case AUTO_APPLIED -> a.green("[APPLIED]       ");
            case APPROVED_APPLIED -> a.green("[APPROVED]      ");
            case PENDING_APPROVAL -> a.yellow("[NEEDS APPROVAL]");
            case DRY_RUN -> a.cyan("[DRY RUN]       ");
            case BLOCKED_BY_POLICY -> a.red("[BLOCKED]       ");
            case REJECTED -> a.red("[REJECTED]      ");
            case FAILED_STALE -> a.red("[STALE]         ");
        });
        return label.get(0);
    }

    private static int terminalWidth() {
        try {
            return Math.min(120, Integer.parseInt(System.getenv("COLUMNS")));
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private static void usage() {
        System.err.println("Usage: ask.sh [--dry-run] [--raw] [--no-color] [--verbose] [--out file.json] \"question\"");
        System.exit(2);
    }

    /** Tiny ANSI helper. */
    private record Ansi(boolean on) {
        String wrap(String code, String s) {
            return on ? "\u001B[" + code + "m" + s + "\u001B[0m" : s;
        }

        String bold(String s) {
            return wrap("1", s);
        }

        String dim(String s) {
            return wrap("2", s);
        }

        String green(String s) {
            return wrap("1;32", s);
        }

        String red(String s) {
            return wrap("1;31", s);
        }

        String yellow(String s) {
            return wrap("1;33", s);
        }

        String cyan(String s) {
            return wrap("1;36", s);
        }

        String section(String title) {
            return wrap("1;36", "══ " + title + " " + "═".repeat(Math.max(3, 40 - title.length())));
        }
    }
}
