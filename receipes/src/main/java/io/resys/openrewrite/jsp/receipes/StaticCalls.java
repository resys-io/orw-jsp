package io.resys.openrewrite.jsp.receipes;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds static method calls in JSP Java code by naming convention (text-level, without a type
 * checker), resolves their class through the page's imports, and matches them against exclusion
 * patterns, for {@link FindStaticMethodCalls}.
 */
final class StaticCalls {

    /**
     * A static call: the class as written (e.g. {@code DateUtils} or {@code com.acme.Util}), the
     * method, and the offset of the call in the code.
     */
    record Call(String writtenClass, String method, int offset) {
    }

    /**
     * {@code Class.method(}, {@code pkg.Class.method(}, {@code Outer.Inner.method(}, and
     * {@code Class.<T>method(}: lowercase package segments, then capitalized class segments.
     */
    private static final Pattern CALL = Pattern.compile(
            "(?<![\\w$.])((?:[a-z_$][\\w$]*\\s*\\.\\s*)*[A-Z][\\w$]*(?:\\s*\\.\\s*[A-Z][\\w$]*)*)" +
            "\\s*\\.\\s*(?:<[^<>()]*>\\s*)?([A-Za-z_$][\\w$]*)\\s*\\(");

    private static final Pattern NEW_BEFORE = Pattern.compile("\\bnew\\s*$");

    private static final Pattern CONSTANT = Pattern.compile("[A-Z][A-Z0-9_]*");

    /**
     * {@code java.lang} classes usable without an import.
     */
    private static final Set<String> JAVA_LANG = Set.of(
            "Boolean", "Byte", "Character", "Class", "Double", "Enum", "Float", "Integer", "Long", "Math",
            "Number", "Object", "Runtime", "Short", "StrictMath", "String", "StringBuffer", "StringBuilder",
            "System", "Thread", "ThreadLocal", "Void", "ProcessHandle", "Record");

    private StaticCalls() {
    }

    /**
     * @param localNames names the page's Java code declares (variables, fields), which aren't classes
     *                   even when capitalized.
     */
    static List<Call> find(String code, Set<String> localNames) {
        String masked = JspVariables.maskLiteralsAndComments(code);
        List<Call> calls = new ArrayList<>();
        Matcher m = CALL.matcher(masked);
        while (m.find()) {
            String written = m.group(1).replaceAll("\\s", "");
            String[] segments = written.split("\\.");
            String firstCapitalized = null;
            for (String segment : segments) {
                if (Character.isUpperCase(segment.charAt(0))) {
                    firstCapitalized = segment;
                    break;
                }
            }
            if (firstCapitalized == null || localNames.contains(segments[0])) {
                continue;
            }
            if (NEW_BEFORE.matcher(masked.substring(Math.max(0, m.start() - 10), m.start())).find()) {
                continue; // new Outer.Inner(...): a constructor
            }
            String last = segments[segments.length - 1];
            if (segments.length > 1 && Character.isUpperCase(segments[segments.length - 2].charAt(0)) &&
                CONSTANT.matcher(last).matches() && last.length() > 1) {
                continue; // Status.ACTIVE.name(): an instance method on a constant
            }
            calls.add(new Call(written, m.group(2), m.start()));
        }
        return calls;
    }

    /**
     * @param imports           explicit single-type imports, by simple name.
     * @param wildcardPackages  packages imported with {@code .*} (not including {@code java.lang}).
     * @return the fully qualified class names the written class may be, most likely first: exactly
     * one if it's qualified, explicitly imported, or a {@code java.lang} class; otherwise the
     * candidates from wildcard imports, and the name as written.
     */
    static List<String> candidates(String written, Map<String, String> imports, Set<String> wildcardPackages) {
        if (Character.isLowerCase(written.charAt(0))) {
            return List.of(written);
        }
        int dot = written.indexOf('.');
        String simple = dot < 0 ? written : written.substring(0, dot);
        String nested = dot < 0 ? "" : written.substring(dot);
        String imported = imports.get(simple);
        if (imported != null) {
            return List.of(imported + nested);
        }
        if (JAVA_LANG.contains(simple)) {
            return List.of("java.lang." + written);
        }
        Set<String> candidates = new LinkedHashSet<>();
        for (String pkg : wildcardPackages) {
            candidates.add(pkg + "." + written);
        }
        candidates.add(written);
        return new ArrayList<>(candidates);
    }

    /**
     * An exclusion pattern matched against {@code fully.qualified.Class.method}: {@code *} matches
     * within one name segment, {@code **} across segments.
     */
    static Pattern exclusion(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^.]*");
                }
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    static boolean excluded(List<String> candidates, String method, List<Pattern> exclusions) {
        for (String candidate : candidates) {
            for (Pattern exclusion : exclusions) {
                if (exclusion.matcher(candidate + "." + method).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    static @Nullable String unique(List<String> candidates) {
        return candidates.size() == 1 ? candidates.get(0) : null;
    }
}
