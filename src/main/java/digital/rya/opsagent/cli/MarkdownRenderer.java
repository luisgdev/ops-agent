package digital.rya.opsagent.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the Markdown the model writes into readable terminal text: headings, bold, inline
 * code, lists, quotes, code blocks and boxed tables, wrapped to the terminal width.
 * Colours are optional (off for pipes, files and NO_COLOR). Used by the CLI only.
 */
final class MarkdownRenderer {

    private static final String RESET = "\u001B[0m";
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w*])\\*(?!\\s)([^*]+?)(?<!\\s)\\*(?![\\w*])");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)]\\((https?://[^)]+)\\)");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    private final boolean color;
    private final int width;

    MarkdownRenderer(boolean color, int width) {
        this.color = color;
        this.width = Math.max(60, width);
    }

    String render(String markdown) {
        List<String> lines = List.of(markdown.replace("\r", "").split("\n", -1));
        StringBuilder out = new StringBuilder();
        boolean inCode = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                out.append("    ").append(style("2", line)).append('\n');
                continue;
            }
            if (trimmed.isEmpty()) {
                out.append('\n');
                continue;
            }
            if (trimmed.matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                out.append(style("2", "─".repeat(width))).append('\n');
                continue;
            }
            if (trimmed.startsWith("|") && i + 1 < lines.size() && TABLE_SEP.matcher(lines.get(i + 1)).matches()) {
                List<String> rows = new ArrayList<>();
                rows.add(line);
                int j = i + 2;
                while (j < lines.size() && lines.get(j).trim().startsWith("|")) {
                    rows.add(lines.get(j));
                    j++;
                }
                table(rows, out);
                i = j - 1;
                continue;
            }
            Matcher h = Pattern.compile("^(#{1,6})\\s+(.*)$").matcher(trimmed);
            if (h.matches()) {
                String text = inline(h.group(2));
                if (h.group(1).length() <= 2) {
                    out.append('\n').append(style("1;36", strip(text).toUpperCase())).append('\n')
                            .append(style("36", "─".repeat(Math.min(width, strip(text).length())))).append('\n');
                } else {
                    out.append(style("1;36", strip(text))).append('\n');
                }
                continue;
            }
            if (trimmed.startsWith(">")) {
                String text = inline(trimmed.replaceFirst("^>\\s?", ""));
                wrap(text, width - 4, style("33", "  ▎ "), style("33", "  ▎ "), out);
                continue;
            }
            Matcher b = BULLET.matcher(line);
            if (b.matches()) {
                String pad = " ".repeat(2 + b.group(1).length());
                wrap(inline(b.group(2)), width - pad.length() - 2, pad + style("36", "• "), pad + "  ", out);
                continue;
            }
            Matcher n = NUMBERED.matcher(line);
            if (n.matches()) {
                String pad = " ".repeat(2 + n.group(1).length());
                String num = n.group(2) + ". ";
                wrap(inline(n.group(3)), width - pad.length() - num.length(), pad + style("36", num),
                        pad + " ".repeat(num.length()), out);
                continue;
            }
            wrap(inline(trimmed), width, "", "", out);
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").stripTrailing() + "\n";
    }

    // ---- inline ----

    private String inline(String s) {
        s = LINK.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + " (" + m.group(2) + ")"));
        s = CODE.matcher(s).replaceAll(m -> Matcher.quoteReplacement(color ? "\u001B[36m" + m.group(1) + "\u001B[39m" : m.group(1)));
        s = BOLD.matcher(s).replaceAll(m -> Matcher.quoteReplacement(color ? "\u001B[1m" + m.group(1) + "\u001B[22m" : m.group(1)));
        s = ITALIC.matcher(s).replaceAll(m -> Matcher.quoteReplacement(color ? "\u001B[3m" + m.group(1) + "\u001B[23m" : m.group(1)));
        return s;
    }

    private String style(String code, String s) {
        return color ? "\u001B[" + code + "m" + s + RESET : s;
    }

    static String strip(String s) {
        return ANSI.matcher(s).replaceAll("");
    }

    static int visible(String s) {
        return strip(s).length();
    }

    // ---- wrapping ----

    /** Word-wraps text that may contain ANSI codes, measuring only visible characters. */
    private static List<String> wrapLines(String text, int max) {
        List<String> res = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int len = 0;
        for (String word : text.split(" +")) {
            int w = visible(word);
            if (len > 0 && len + 1 + w > max) {
                res.add(cur.toString());
                cur.setLength(0);
                len = 0;
            }
            if (len > 0) {
                cur.append(' ');
                len++;
            }
            cur.append(word);
            len += w;
        }
        res.add(cur.toString());
        return res;
    }

    private void wrap(String text, int max, String first, String rest, StringBuilder out) {
        List<String> ls = wrapLines(text, Math.max(20, max));
        for (int k = 0; k < ls.size(); k++) {
            out.append(k == 0 ? first : rest).append(ls.get(k)).append('\n');
        }
    }

    // ---- tables ----

    private static List<String> cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) {
            r = r.substring(1);
        }
        if (r.endsWith("|")) {
            r = r.substring(0, r.length() - 1);
        }
        List<String> res = new ArrayList<>();
        for (String c : r.split("(?<!\\\\)\\|", -1)) {
            res.add(c.trim().replace("\\|", "|"));
        }
        return res;
    }

    private void table(List<String> rawRows, StringBuilder out) {
        List<List<String>> rows = new ArrayList<>();
        for (String r : rawRows) {
            List<String> cs = new ArrayList<>();
            for (String c : cells(r)) {
                cs.add(inline(c));
            }
            rows.add(cs);
        }
        int cols = rows.stream().mapToInt(List::size).max().orElse(0);
        int[] w = new int[cols];
        for (List<String> r : rows) {
            for (int c = 0; c < r.size(); c++) {
                w[c] = Math.max(w[c], longestWord(r.get(c)));
            }
        }
        int[] want = new int[cols];
        for (List<String> r : rows) {
            for (int c = 0; c < r.size(); c++) {
                want[c] = Math.max(want[c], visible(r.get(c)));
            }
        }
        int budget = width - (3 * cols + 1);
        int[] cw = want.clone();
        while (sum(cw) > budget) {
            int widest = 0;
            for (int c = 1; c < cols; c++) {
                if (cw[c] > cw[widest]) {
                    widest = c;
                }
            }
            if (cw[widest] <= Math.max(w[widest], 8)) {
                break;
            }
            cw[widest]--;
        }

        out.append(border('┌', '┬', '┐', cw)).append('\n');
        for (int ri = 0; ri < rows.size(); ri++) {
            List<String> r = rows.get(ri);
            List<List<String>> wrapped = new ArrayList<>();
            int height = 1;
            for (int c = 0; c < cols; c++) {
                List<String> l = wrapLines(c < r.size() ? r.get(c) : "", cw[c]);
                wrapped.add(l);
                height = Math.max(height, l.size());
            }
            for (int k = 0; k < height; k++) {
                StringBuilder line = new StringBuilder(style("2", "│"));
                for (int c = 0; c < cols; c++) {
                    String cell = k < wrapped.get(c).size() ? wrapped.get(c).get(k) : "";
                    if (ri == 0 && color) {
                        cell = "\u001B[1;36m" + strip(cell) + RESET;
                    }
                    line.append(' ').append(cell).append(" ".repeat(Math.max(0, cw[c] - visible(cell)))).append(' ')
                            .append(style("2", "│"));
                }
                out.append(line).append('\n');
            }
            if (ri == 0) {
                out.append(border('├', '┼', '┤', cw)).append('\n');
            }
        }
        out.append(border('└', '┴', '┘', cw)).append('\n');
    }

    private String border(char l, char m, char r, int[] cw) {
        StringBuilder sb = new StringBuilder().append(l);
        for (int c = 0; c < cw.length; c++) {
            sb.append("─".repeat(cw[c] + 2)).append(c == cw.length - 1 ? r : m);
        }
        return style("2", sb.toString());
    }

    private static int longestWord(String s) {
        int m = 0;
        for (String w : s.split(" +")) {
            m = Math.max(m, visible(w));
        }
        return m;
    }

    private static int sum(int[] a) {
        int s = 0;
        for (int x : a) {
            s += x;
        }
        return s;
    }
}
