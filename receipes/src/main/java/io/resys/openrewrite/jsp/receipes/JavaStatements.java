package io.resys.openrewrite.jsp.receipes;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A text-level scan of the statements in one chunk of JSP Java code (a scriptlet), for
 * {@link JavaVariableMigrator}: where variables are declared and written at statement level, and
 * where a statement could be inserted right after each such write so that the variable's value can
 * be mirrored somewhere else. Not a Java parser; anything it doesn't recognize is left for
 * {@link #allWrites} to flag as an unrecognized write.
 */
final class JavaStatements {

    enum Kind {
        /** {@code T x = ...;} (insertion after the {@code ;}) or {@code T x;} (no insertion). */
        DECLARATION,
        /** A for-each or classic for loop variable (insertion after the body's <code>{</code>). */
        LOOP_VARIABLE,
        /** {@code x = ...;}, {@code x += ...;}, {@code x++;}, {@code ++x;} (insertion after the {@code ;}). */
        ASSIGNMENT
    }

    /**
     * A recognized declaration or write of {@code name}.
     *
     * @param namePosition where the name occurs (as a write) in the code.
     * @param insertAt     where a statement can be inserted to run right after the write, or
     *                     {@code -1} if there is no write to follow (a declaration without
     *                     initializer, or a loop without a braced body).
     * @param headerEnd    for a loop variable, the end of the loop header, whose writes to the
     *                     variable ({@code i++}) are part of the loop; {@code -1} otherwise.
     */
    record Site(String name, Kind kind, int namePosition, int insertAt, int headerEnd) {
    }

    private static final String NAME = "[A-Za-z_$][\\w$]*";
    private static final String TYPE =
            "(?:" + NAME + "\\s*\\.\\s*)*" + NAME + "(?:\\s*<[^;=(){}]*>)?(?:\\s*\\[\\s*\\])*";

    private static final Pattern DECLARATION = Pattern.compile(
            "(?:final\\s+)?(" + TYPE + ")\\s+(" + NAME + ")\\s*(=(?!=)|;|,)");
    private static final Pattern FOR_EACH = Pattern.compile(
            "for\\s*\\(\\s*(?:final\\s+)?(" + TYPE + ")\\s+(" + NAME + ")\\s*:");
    private static final Pattern FOR_CLASSIC = Pattern.compile(
            "for\\s*\\(\\s*(?:final\\s+)?(" + TYPE + ")\\s+(" + NAME + ")\\s*=(?!=)");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(" + NAME + ")\\s*(?:=(?!=)|[-+*/%&|^]=|<<=|>>>?=)");
    private static final Pattern INCREMENT = Pattern.compile(
            "(?:(" + NAME + ")\\s*(?:\\+\\+|--)|(?:\\+\\+|--)\\s*(" + NAME + "))\\s*;");

    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "break", "case", "catch", "class", "const", "continue", "default", "do",
            "else", "enum", "extends", "finally", "for", "goto", "if", "implements", "import", "instanceof",
            "interface", "native", "new", "package", "return", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "try", "while", "yield", "true", "false", "null");

    private JavaStatements() {
    }

    /**
     * @return the recognized declarations and statement-level writes in the code, in order.
     */
    static List<Site> sites(String code) {
        String masked = JspVariables.maskLiteralsAndComments(code);
        List<Site> sites = new ArrayList<>();
        for (int start : statementStarts(masked)) {
            int at = skipWhitespace(masked, start);
            if (at >= masked.length()) {
                continue;
            }
            Matcher loop = FOR_EACH.matcher(masked).region(at, masked.length());
            if (!loop.lookingAt()) {
                loop = FOR_CLASSIC.matcher(masked).region(at, masked.length());
            }
            if (loop.lookingAt()) {
                if (!KEYWORDS.contains(loop.group(1))) {
                    int open = masked.indexOf('(', at);
                    int close = matching(masked, open);
                    int body = close < 0 ? -1 : skipWhitespace(masked, close + 1);
                    boolean braced = body >= 0 && body < masked.length() && masked.charAt(body) == '{';
                    sites.add(new Site(loop.group(2), Kind.LOOP_VARIABLE, loop.start(2),
                            braced ? body + 1 : -1, close));
                }
                continue;
            }
            Matcher declaration = DECLARATION.matcher(masked).region(at, masked.length());
            if (declaration.lookingAt() && !KEYWORDS.contains(declaration.group(1)) &&
                !KEYWORDS.contains(declaration.group(2))) {
                int end = statementEnd(masked, declaration.start(2));
                for (int[] declarator : declarators(masked, declaration.start(2), end)) {
                    String name = masked.substring(declarator[0], declarator[1]);
                    sites.add(new Site(name, Kind.DECLARATION, declarator[0],
                            declarator[2] == 1 && end >= 0 ? end + 1 : -1, -1));
                }
                continue;
            }
            Matcher assignment = ASSIGNMENT.matcher(masked).region(at, masked.length());
            if (assignment.lookingAt() && !KEYWORDS.contains(assignment.group(1))) {
                int end = statementEnd(masked, assignment.end());
                if (end >= 0) {
                    sites.add(new Site(assignment.group(1), Kind.ASSIGNMENT, assignment.start(1), end + 1, -1));
                }
                continue;
            }
            Matcher increment = INCREMENT.matcher(masked).region(at, masked.length());
            if (increment.lookingAt()) {
                int group = increment.group(1) != null ? 1 : 2;
                sites.add(new Site(increment.group(group), Kind.ASSIGNMENT, increment.start(group),
                        increment.end(), -1));
            }
        }
        return sites;
    }

    /**
     * @return the positions of every write to {@code name} in the code (assignment, compound
     * assignment, increment, decrement), recognized at statement level or not.
     */
    static List<Integer> allWrites(String code, String name) {
        String masked = JspVariables.maskLiteralsAndComments(code);
        Pattern write = Pattern.compile("(?<![\\w$.])(" + Pattern.quote(name) +
                                        ")\\s*(?:=(?!=)|[-+*/%&|^]=|<<=|>>>?=|\\+\\+|--)|(?:\\+\\+|--)\\s*(" +
                                        Pattern.quote(name) + ")(?![\\w$])");
        List<Integer> positions = new ArrayList<>();
        Matcher m = write.matcher(masked);
        while (m.find()) {
            positions.add(m.start(m.group(1) != null ? 1 : 2));
        }
        return positions;
    }

    /**
     * Statement boundaries: the start of the code, and after every {@code ;}, <code>{</code>, or
     * <code>}</code> outside parentheses (so a for header's {@code ;}s don't count).
     */
    private static List<Integer> statementStarts(String masked) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int parens = 0;
        for (int i = 0; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(') {
                parens++;
            } else if (c == ')') {
                parens = Math.max(0, parens - 1);
            } else if (parens == 0 && (c == ';' || c == '{' || c == '}')) {
                starts.add(i + 1);
            }
        }
        return starts;
    }

    /**
     * @return the index of the {@code ;} ending the statement containing {@code from}, at nesting
     * depth 0, or {@code -1} if it doesn't end in this code.
     */
    private static int statementEnd(String masked, int from) {
        int depth = 0;
        for (int i = from; i < masked.length(); i++) {
            char c = masked.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    return -1;
                }
                depth--;
            } else if (c == ';' && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return {start, end, hasInitializer (0/1)} of each declarator ({@code a = 1, b}) of a
     * declaration statement whose first name starts at {@code from}.
     */
    private static List<int[]> declarators(String masked, int from, int end) {
        List<int[]> declarators = new ArrayList<>();
        int stop = end < 0 ? masked.length() : end;
        int depth = 0;
        int partStart = from;
        for (int i = from; i <= stop; i++) {
            char c = i < stop ? masked.charAt(i) : ',';
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                // A declarator is a name followed by "=" or nothing; anything else is a fragment of
                // a type argument list or initializer split at a comma we don't track (<A, B>).
                Matcher name = Pattern.compile("\\s*(" + NAME + ")\\s*(?:(=(?!=))|$)").matcher(masked)
                        .region(partStart, i);
                if (name.lookingAt()) {
                    declarators.add(new int[]{name.start(1), name.end(1), name.group(2) != null ? 1 : 0});
                }
                partStart = i + 1;
            }
        }
        return declarators;
    }

    private static int matching(String masked, int open) {
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < masked.length(); i++) {
            if (masked.charAt(i) == '(') {
                depth++;
            } else if (masked.charAt(i) == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }
}
