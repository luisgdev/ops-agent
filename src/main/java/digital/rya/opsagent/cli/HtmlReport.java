package digital.rya.opsagent.cli;

import digital.rya.opsagent.actions.ActionStatus;
import digital.rya.opsagent.actions.PlatformAction;
import digital.rya.opsagent.agent.AgentResponse;
import digital.rya.opsagent.agent.AgentResponse.ToolCallRecord;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a single self-contained HTML page (inline CSS, no JavaScript, no external requests)
 * from an agent response. Light and dark themes follow the reader's system setting and the
 * page prints cleanly (Print → Save as PDF). Used by the CLI only.
 */
final class HtmlReport {

    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w*])\\*(?!\\s)([^*]+?)(?<!\\s)\\*(?![\\w*])");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    private HtmlReport() {
    }

    static String build(AgentResponse r) {
        StringBuilder h = new StringBuilder(16_000);
        h.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<meta name=\"color-scheme\" content=\"light dark\">\n")
                .append("<title>Rya Ops Agent report</title>\n<style>").append(CSS).append("</style>\n</head>\n<body>\n<main>\n");

        h.append("<header>\n<p class=\"eyebrow\">Rya Ops Agent</p>\n<h1>Operations report</h1>\n<div class=\"chips\">")
                .append(chip(esc(r.model())))
                .append(r.dryRun() ? chip("<span class=\"dot warn\"></span>Dry run · nothing changed") : "")
                .append(chip(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))))
                .append(chip(esc(r.requestId())))
                .append("</div>\n</header>\n");

        h.append("<section class=\"card question\">\n<h2>Question</h2>\n<p>").append(esc(r.question())).append("</p>\n</section>\n");

        h.append("<section class=\"card answer\">\n<h2>Answer</h2>\n<div class=\"prose\">\n")
                .append(markdown(r.answer())).append("</div>\n</section>\n");

        h.append("<section>\n<h2 class=\"section-title\">Actions</h2>\n");
        if (r.actions().isEmpty()) {
            h.append("<p class=\"muted\">The agent proposed no changes.</p>\n");
        }
        for (PlatformAction a : r.actions()) {
            action(h, a);
        }
        h.append("</section>\n");

        if (!r.toolCalls().isEmpty()) {
            h.append("<section>\n<details class=\"card tools-card\">\n<summary>Tool calls (").append(r.toolCalls().size())
                    .append(")</summary>\n<ol class=\"tools\">\n");
            for (ToolCallRecord t : r.toolCalls()) {
                h.append("<li><span class=\"").append(t.ok() ? "ok" : "bad").append("\">").append(t.ok() ? "✓" : "✗")
                        .append("</span> <strong>").append(esc(t.tool())).append("</strong> <code>")
                        .append(esc(String.valueOf(t.input()))).append("</code> <span class=\"muted\">")
                        .append(t.durationMs()).append(" ms</span>")
                        .append(t.ok() ? "" : "<div class=\"bad\">" + esc(String.valueOf(t.error())) + "</div>")
                        .append("</li>\n");
            }
            h.append("</ol>\n</details>\n</section>\n");
        }

        h.append("<footer>\n<div class=\"stat\"><b>").append(r.toolCalls().size()).append("</b><span>tool calls</span></div>\n")
                .append("<div class=\"stat\"><b>").append(r.iterations()).append("</b><span>model calls</span></div>\n")
                .append("<div class=\"stat\"><b>").append(String.format("%,d", r.usage().inputTokens()))
                .append("</b><span>input tokens</span></div>\n")
                .append("<div class=\"stat\"><b>").append(String.format("%,d", r.usage().outputTokens()))
                .append("</b><span>output tokens</span></div>\n");
        if (!"end_turn".equals(r.stopReason())) {
            h.append("<p class=\"note\">The agent stopped with <code>").append(esc(r.stopReason()))
                    .append("</code>; the answer may be incomplete.</p>\n");
        }
        h.append("</footer>\n</main>\n</body>\n</html>\n");
        return h.toString();
    }

    // ---- actions ----

    private static void action(StringBuilder h, PlatformAction a) {
        h.append("<article class=\"card action\">\n<div class=\"action-head\"><span class=\"badge ").append(cls(a.status()))
                .append("\">").append(label(a.status())).append("</span><span class=\"id\">").append(esc(a.id()))
                .append("</span></div>\n<h3>").append(esc(AskCli.describe(a))).append("</h3>\n")
                .append("<dl>\n<dt>Why</dt><dd>").append(esc(a.reason())).append("</dd>\n");
        if (a.policyNote() != null && !a.policyNote().isBlank()) {
            h.append("<dt>Policy</dt><dd>").append(esc(a.policyNote())).append("</dd>\n");
        }
        h.append("</dl>\n");
        for (String w : a.warnings()) {
            h.append("<p class=\"warning\">").append(esc(w)).append("</p>\n");
        }
        h.append("</article>\n");
    }

    private static String cls(ActionStatus s) {
        return switch (s) {
            case AUTO_APPLIED, APPROVED_APPLIED -> "good";
            case PENDING_APPROVAL -> "warn";
            case DRY_RUN -> "info";
            case BLOCKED_BY_POLICY, REJECTED, FAILED_STALE -> "bad";
        };
    }

    private static String label(ActionStatus s) {
        return switch (s) {
            case AUTO_APPLIED -> "Applied";
            case APPROVED_APPLIED -> "Approved";
            case PENDING_APPROVAL -> "Needs approval";
            case DRY_RUN -> "Dry run";
            case BLOCKED_BY_POLICY -> "Blocked";
            case REJECTED -> "Rejected";
            case FAILED_STALE -> "Stale";
        };
    }

    private static String chip(String inner) {
        return "<span class=\"chip\">" + inner + "</span>";
    }

    // ---- markdown → html ----

    static String markdown(String md) {
        List<String> lines = List.of(md.replace("\r", "").split("\n", -1));
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String t = line.trim();
            if (t.isEmpty()) {
                i++;
            } else if (t.startsWith("```")) {
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.size() && !lines.get(i).trim().startsWith("```")) {
                    code.append(esc(lines.get(i))).append('\n');
                    i++;
                }
                i++;
                out.append("<pre><code>").append(code).append("</code></pre>\n");
            } else if (t.matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                out.append("<hr>\n");
                i++;
            } else if (t.startsWith("|") && i + 1 < lines.size() && TABLE_SEP.matcher(lines.get(i + 1)).matches()) {
                List<String> rows = new ArrayList<>();
                rows.add(line);
                i += 2;
                while (i < lines.size() && lines.get(i).trim().startsWith("|")) {
                    rows.add(lines.get(i++));
                }
                table(rows, out);
            } else if (HEADING.matcher(t).matches()) {
                Matcher m = HEADING.matcher(t);
                m.matches();
                int level = Math.min(6, m.group(1).length() + 1); // page h1 is the title
                out.append("<h").append(level).append('>').append(inline(m.group(2))).append("</h").append(level).append(">\n");
                i++;
            } else if (t.startsWith(">")) {
                List<String> q = new ArrayList<>();
                while (i < lines.size() && lines.get(i).trim().startsWith(">")) {
                    q.add(lines.get(i).trim().replaceFirst("^>\\s?", ""));
                    i++;
                }
                out.append("<blockquote>").append(inline(String.join(" ", q))).append("</blockquote>\n");
            } else if (BULLET.matcher(line).matches() || NUMBERED.matcher(line).matches()) {
                boolean ordered = NUMBERED.matcher(line).matches();
                out.append(ordered ? "<ol>\n" : "<ul>\n");
                while (i < lines.size()) {
                    Matcher b = (ordered ? NUMBERED : BULLET).matcher(lines.get(i));
                    if (!b.matches()) {
                        break;
                    }
                    StringBuilder item = new StringBuilder(b.group(1));
                    i++;
                    while (i < lines.size() && !lines.get(i).isBlank() && lines.get(i).startsWith("  ")
                            && !BULLET.matcher(lines.get(i)).matches() && !NUMBERED.matcher(lines.get(i)).matches()) {
                        item.append(' ').append(lines.get(i).trim());
                        i++;
                    }
                    out.append("<li>").append(inline(item.toString())).append("</li>\n");
                }
                out.append(ordered ? "</ol>\n" : "</ul>\n");
            } else {
                StringBuilder p = new StringBuilder(t);
                i++;
                while (i < lines.size() && !lines.get(i).isBlank() && startsPlainLine(lines.get(i), lines, i)) {
                    p.append(' ').append(lines.get(i).trim());
                    i++;
                }
                out.append("<p>").append(inline(p.toString())).append("</p>\n");
            }
        }
        return out.toString();
    }

    private static boolean startsPlainLine(String line, List<String> lines, int i) {
        String t = line.trim();
        return !(t.startsWith("```") || t.startsWith("|") || t.startsWith(">") || HEADING.matcher(t).matches()
                || BULLET.matcher(line).matches() || NUMBERED.matcher(line).matches() || t.matches("^(-{3,}|\\*{3,}|_{3,})$"));
    }

    private static void table(List<String> rows, StringBuilder out) {
        out.append("<div class=\"table-wrap\"><table>\n");
        for (int r = 0; r < rows.size(); r++) {
            String row = rows.get(r).trim();
            if (row.startsWith("|")) {
                row = row.substring(1);
            }
            if (row.endsWith("|")) {
                row = row.substring(0, row.length() - 1);
            }
            String tag = r == 0 ? "th" : "td";
            if (r == 0) {
                out.append("<thead>");
            } else if (r == 1) {
                out.append("<tbody>");
            }
            out.append("<tr>");
            for (String c : row.split("(?<!\\\\)\\|", -1)) {
                out.append('<').append(tag).append('>').append(inline(c.trim().replace("\\|", "|")))
                        .append("</").append(tag).append('>');
            }
            out.append("</tr>");
            if (r == 0) {
                out.append("</thead>");
            }
            out.append('\n');
        }
        out.append("</tbody></table></div>\n");
    }

    /** Escapes first, then applies inline formatting, so model text can never inject markup. */
    private static String inline(String s) {
        s = esc(s);
        s = CODE.matcher(s).replaceAll(m -> "<code>" + Matcher.quoteReplacement(m.group(1)) + "</code>");
        s = BOLD.matcher(s).replaceAll(m -> "<strong>" + Matcher.quoteReplacement(m.group(1)) + "</strong>");
        s = ITALIC.matcher(s).replaceAll(m -> "<em>" + Matcher.quoteReplacement(m.group(1)) + "</em>");
        s = LINK.matcher(s).replaceAll(m -> "<a href=\"" + Matcher.quoteReplacement(m.group(2))
                + "\" rel=\"noopener noreferrer\">" + Matcher.quoteReplacement(m.group(1)) + "</a>");
        return s;
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS = """
            :root{--bg:#f5f6f8;--card:#fff;--text:#1b2230;--muted:#667085;--line:#e4e7ec;--accent:#4f46e5;--accent-soft:#eef0ff;
            --good:#067647;--good-bg:#e6f6ee;--warn:#b54708;--warn-bg:#fff4e0;--bad:#b42318;--bad-bg:#fdeceb;--info:#175cd3;--info-bg:#e8f1ff;
            --code-bg:#f2f4f7;--shadow:0 1px 2px rgba(16,24,40,.06),0 4px 16px rgba(16,24,40,.05)}
            @media (prefers-color-scheme:dark){:root{--bg:#0e121a;--card:#161b26;--text:#e6e9f0;--muted:#98a2b3;--line:#272e3d;--accent:#8b93ff;--accent-soft:#1d2240;
            --good:#4ade80;--good-bg:#12301f;--warn:#fbbf24;--warn-bg:#35280d;--bad:#f87171;--bad-bg:#3a1716;--info:#7db4ff;--info-bg:#12284a;
            --code-bg:#1e2431;--shadow:none}}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--text);font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,"Helvetica Neue",sans-serif;-webkit-font-smoothing:antialiased}
            main{max-width:960px;margin:0 auto;padding:40px 20px 64px}
            header{margin-bottom:28px}
            .eyebrow{margin:0;color:var(--accent);font-weight:700;font-size:.8rem;letter-spacing:.12em;text-transform:uppercase}
            h1{margin:.2em 0 .5em;font-size:2rem;line-height:1.2;letter-spacing:-.02em}
            .chips{display:flex;flex-wrap:wrap;gap:8px}
            .chip{display:inline-flex;align-items:center;gap:6px;padding:3px 12px;border:1px solid var(--line);border-radius:999px;background:var(--card);color:var(--muted);font-size:.82rem}
            .dot{width:8px;height:8px;border-radius:50%;background:var(--muted)}.dot.warn{background:var(--warn)}
            .card{background:var(--card);border:1px solid var(--line);border-radius:16px;padding:22px 26px;margin:0 0 18px;box-shadow:var(--shadow)}
            .card>h2,.section-title{margin:0 0 .6em;font-size:.78rem;letter-spacing:.1em;text-transform:uppercase;color:var(--muted);font-weight:700}
            .section-title{margin-top:30px}
            .question{border-left:4px solid var(--accent)}.question p{margin:0;font-size:1.05rem}
            .prose h2,.prose h3,.prose h4{margin:1.5em 0 .4em;line-height:1.3;letter-spacing:-.01em}
            .prose h2{font-size:1.25rem}.prose h3{font-size:1.1rem}.prose h4{font-size:1rem}
            .prose>:first-child{margin-top:0}.prose p{margin:.7em 0}
            .prose ul,.prose ol{padding-left:1.4em;margin:.6em 0}.prose li{margin:.35em 0}.prose li::marker{color:var(--accent)}
            .prose blockquote{margin:1em 0;padding:.6em 1em;border-left:4px solid var(--accent);background:var(--accent-soft);border-radius:0 10px 10px 0}
            .prose hr{border:0;border-top:1px solid var(--line);margin:1.5em 0}
            code{font:.88em ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;background:var(--code-bg);padding:.12em .4em;border-radius:6px}
            pre{background:var(--code-bg);padding:14px 16px;border-radius:10px;overflow-x:auto}pre code{padding:0;background:none}
            a{color:var(--accent)}
            .table-wrap{overflow-x:auto;margin:1em 0;border:1px solid var(--line);border-radius:12px}
            table{border-collapse:collapse;width:100%;font-size:.92rem}
            th{background:var(--code-bg);text-align:left;font-size:.76rem;letter-spacing:.06em;text-transform:uppercase;color:var(--muted)}
            th,td{padding:10px 14px;vertical-align:top;border-bottom:1px solid var(--line)}
            tbody tr:last-child td{border-bottom:0}tbody tr:hover{background:var(--accent-soft)}
            .action-head{display:flex;align-items:center;justify-content:space-between;margin-bottom:6px}
            .id{font:.82rem ui-monospace,Menlo,Consolas,monospace;color:var(--muted)}
            .action h3{margin:.1em 0 .6em;font-size:1.1rem}
            .badge{display:inline-block;padding:2px 12px;border-radius:999px;font-size:.78rem;font-weight:700;letter-spacing:.03em}
            .badge.good{color:var(--good);background:var(--good-bg)}.badge.warn{color:var(--warn);background:var(--warn-bg)}
            .badge.bad{color:var(--bad);background:var(--bad-bg)}.badge.info{color:var(--info);background:var(--info-bg)}
            dl{display:grid;grid-template-columns:72px 1fr;gap:6px 14px;margin:0}dt{color:var(--muted);font-size:.85rem;padding-top:.1em}dd{margin:0}
            .warning{margin:12px 0 0;padding:10px 14px;border-radius:10px;background:var(--warn-bg);color:var(--warn);font-size:.92rem}
            .warning::before{content:"⚠ "}
            details summary{cursor:pointer;font-weight:600;color:var(--muted)}
            .tools{margin:14px 0 0;padding-left:1.3em;font-size:.9rem}.tools li{margin:.5em 0;word-break:break-word}
            .ok{color:var(--good);font-weight:700}.bad{color:var(--bad)}.muted{color:var(--muted)}
            footer{display:flex;flex-wrap:wrap;gap:12px;margin-top:30px;align-items:stretch}
            .stat{flex:1 1 130px;background:var(--card);border:1px solid var(--line);border-radius:14px;padding:12px 16px;display:flex;flex-direction:column}
            .stat b{font-size:1.4rem;letter-spacing:-.02em}.stat span{color:var(--muted);font-size:.8rem}
            .note{flex-basis:100%;margin:0;padding:10px 14px;border-radius:10px;background:var(--warn-bg);color:var(--warn)}
            @page{margin:14mm}
            @media print{
            html,body{background:#fff}body{color:#000;font-size:11pt;-webkit-print-color-adjust:exact;print-color-adjust:exact}
            main{padding:0;max-width:none}
            .card,.stat,.table-wrap{box-shadow:none;overflow:visible}
            .answer,.tools-card{break-inside:auto;page-break-inside:auto}
            .action,.stat,.question,.warning,blockquote,pre,tr,li{break-inside:avoid;page-break-inside:avoid}
            h2,h3,h4,.section-title,.action-head{break-after:avoid;page-break-after:avoid}
            tbody tr:hover{background:none}
            thead{display:table-header-group}
            a{color:inherit;text-decoration:none}}
            @media (max-width:600px){main{padding:24px 14px}.card{padding:16px}dl{grid-template-columns:1fr}}
            """;
}
