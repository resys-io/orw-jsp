package io.resys.orw.jsp.receipes;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Include/exclude patterns on paths with {@code /} separators: {@code *} matches within one path
 * segment, {@code **} across segments (also none: <code>/**&#47;*.jsp</code> matches {@code /a.jsp}),
 * {@code ?} one character.
 */
final class PathGlob {

    private final List<Pattern> includes;
    private final List<Pattern> excludes;

    PathGlob(List<String> includes, List<String> excludes) {
        this.includes = includes.stream().filter(p -> !p.isBlank()).map(PathGlob::compile).toList();
        this.excludes = excludes.stream().filter(p -> !p.isBlank()).map(PathGlob::compile).toList();
    }

    /**
     * @return whether the path matches an include pattern and no exclude pattern.
     */
    boolean matches(String path) {
        return includes.stream().anyMatch(p -> p.matcher(path).matches()) &&
               excludes.stream().noneMatch(p -> p.matcher(path).matches());
    }

    static Pattern compile(String glob) {
        StringBuilder regex = new StringBuilder();
        String g = glob.trim();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*' && i + 1 < g.length() && g.charAt(i + 1) == '*') {
                boolean slashAfter = i + 2 < g.length() && g.charAt(i + 2) == '/';
                // "**/" may match no directories at all.
                regex.append(slashAfter ? "(?:.*/)?" : ".*");
                i += slashAfter ? 2 : 1;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
