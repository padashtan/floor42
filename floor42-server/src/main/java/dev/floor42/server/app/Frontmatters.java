package dev.floor42.server.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The frontmatter of a skill or an agent, read line by line ({@link ClaudeAssets#frontmatter}). */
final class Frontmatters {

    private Frontmatters() {}

    /** The line that closes the frontmatter; -1 when none does (a last line cut by the byte cap is not one). */
    static int closing(List<String> lines, boolean truncated) {
        int end = -1;
        for (int i = 1; i < lines.size(); i++) {
            String l = lines.get(i).strip();
            if (l.equals("---") || l.equals("...")) {
                end = i;
                break;
            }
        }
        return end == lines.size() - 1 && truncated ? -1 : end;
    }

    static ClaudeAssets.Frontmatter fields(List<String> lines, int end) {
        Map<String, String> fields = new LinkedHashMap<>();
        String problem = null;
        int i = 1;
        while (i < end) {
            String line = lines.get(i);
            i++;
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            var m = ClaudeAssets.KEY.matcher(line);
            if (Character.isWhitespace(line.charAt(0)) || !m.matches()) {
                problem = "malformed";
                continue;
            }
            String key = m.group(1).toLowerCase(Locale.ROOT);
            List<String> more = new ArrayList<>();
            while (i < end
                    && (lines.get(i).isBlank()
                            || Character.isWhitespace(lines.get(i).charAt(0)))) {
                if (!lines.get(i).isBlank()) more.add(lines.get(i).strip());
                i++;
            }
            if (!fields.containsKey(key))
                fields.put(key, ClaudeAssets.unquote(value(m.group(2).strip(), more)));
        }
        return new ClaudeAssets.Frontmatter(fields, problem);
    }

    /** A value with its continuation lines: a block scalar, a {@code - item} list, or a folded line. */
    private static String value(String value, List<String> more) {
        if (value.isEmpty() || value.matches("[|>][+-]?")) {
            boolean list = !more.isEmpty() && more.stream().allMatch(s -> s.startsWith("- "));
            return list
                    ? String.join(
                            ", ",
                            more.stream()
                                    .map(s ->
                                            ClaudeAssets.unquote(s.substring(2).strip()))
                                    .toList())
                    : String.join(" ", more);
        }
        return more.isEmpty() ? value : value + " " + String.join(" ", more);
    }
}
