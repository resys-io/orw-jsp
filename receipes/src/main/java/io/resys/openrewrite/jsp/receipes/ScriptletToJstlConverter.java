package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.tree.Jsp;
import org.jspecify.annotations.Nullable;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.openrewrite.Tree.randomId;

/**
 * Replaces scriptlet control structures, variable assignments, and outputs with JSTL and EL, for
 * {@link ConvertScriptletsToJstl}. Works on one page's own content.
 */
final class ScriptletToJstlConverter {

    /**
     * One construct and what happened to it.
     *
     * @param node the scriptlet (or expression) it starts at, in the original page, for its line.
     */
    record Conversion(String construct, boolean converted, String detail, Jsp node) {
    }

    private static final String NAME = "[A-Za-z_$][\\w$]*";
    private static final String TYPE =
            "(?:" + NAME + "\\s*\\.\\s*)*" + NAME + "(?:\\s*<[^;=(){}]*>)?(?:\\s*\\[\\s*\\])*";

    private static final Pattern KEYWORD_AT = Pattern.compile("(if|for)\\s*\\(");
    private static final Pattern FOR_EACH_HEADER = Pattern.compile(
            "\\s*(?:final\\s+)?" + TYPE + "\\s+(" + NAME + ")\\s*:(.*)", Pattern.DOTALL);
    private static final Pattern FOR_COUNT_HEADER = Pattern.compile(
            "\\s*int\\s+(" + NAME + ")\\s*=([^;]+);\\s*(" + NAME + ")\\s*(<=?)([^;]+);\\s*" +
            "(?:(" + NAME + ")\\s*\\+\\+|\\+\\+\\s*(" + NAME + ")|(" + NAME + ")\\s*\\+=\\s*1)\\s*", Pattern.DOTALL);
    private static final Pattern ELSE_IF = Pattern.compile("\\s*else\\s+if\\s*\\(");
    private static final Pattern ELSE = Pattern.compile("\\s*else\\s*\\{\\s*");
    private static final Pattern ASSIGNMENT_SCRIPTLET = Pattern.compile(
            "\\s*(?:(?:final\\s+)?" + TYPE + "\\s+)?(" + NAME + ")\\s*=(?!=)(.*?);\\s*" +
            "(?:pageContext\\s*\\.\\s*setAttribute\\s*\\(\\s*\"(" + NAME + ")\"\\s*,\\s*(" + NAME + ")\\s*\\)\\s*;\\s*)?",
            Pattern.DOTALL);

    private final String corePrefix;
    private final @Nullable String functionsPrefix;
    private final boolean outputsWithCOut;
    private final boolean convertOutputs;
    private final Set<String> mirrored;

    final List<Conversion> conversions = new ArrayList<>();
    boolean usedCore;
    boolean usedFunctions;

    /**
     * @param functionsPrefix the prefix to use for {@code fn:} functions, or {@code null} if the
     *                        JSTL version has none (1.0).
     * @param outputsWithCOut write outputs as {@code <c:out escapeXml="false">} instead of template
     *                        text EL, for containers with EL off (JSTL 1.0 evaluates EL itself).
     * @param convertOutputs  whether to convert {@code <%= %>} outputs at all.
     * @param mirrored        Java variables mirrored into same-named page attributes (and other
     *                        names EL can see).
     */
    ScriptletToJstlConverter(String corePrefix, @Nullable String functionsPrefix, boolean outputsWithCOut,
                             boolean convertOutputs, Set<String> mirrored) {
        this.corePrefix = corePrefix;
        this.functionsPrefix = functionsPrefix;
        this.outputsWithCOut = outputsWithCOut;
        this.convertOutputs = convertOutputs;
        this.mirrored = mirrored;
    }

    /**
     * Mirrored variables, and names existing JSTL/Struts tags define, on the page.
     */
    static Set<String> elVisibleNames(List<Jsp.Content> nodes) {
        Set<String> names = new HashSet<>();
        collectElVisible(nodes, names);
        return names;
    }

    private static void collectElVisible(List<Jsp.Content> nodes, Set<String> names) {
        Pattern mirror = Pattern.compile(
                "pageContext\\s*\\.\\s*setAttribute\\s*\\(\\s*\"(" + NAME + ")\"\\s*,\\s*(" + NAME + ")\\s*\\)");
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Scriptlet) {
                Matcher m = mirror.matcher(((Jsp.Scriptlet) node).getCodeSource());
                while (m.find()) {
                    if (m.group(1).equals(m.group(2))) {
                        names.add(m.group(1));
                    }
                }
            } else if (node instanceof Jsp.Tag) {
                for (Jsp.Attribute attribute : ((Jsp.Tag) node).getAttributes()) {
                    if (Set.of("var", "id").contains(attribute.getName()) &&
                        attribute.getValue().getValue().matches(NAME)) {
                        names.add(attribute.getValue().getValue());
                    }
                }
                if (((Jsp.Tag) node).getBody() != null) {
                    collectElVisible(((Jsp.Tag) node).getBody(), names);
                }
            } else if (node instanceof Jsp.Directive && ((Jsp.Directive) node).getIncludedFile() != null) {
                collectElVisible(((Jsp.Directive) node).getIncludedFile().getNodes(), names);
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Control structures and outputs
    // -----------------------------------------------------------------------------------------

    List<Jsp.Content> convert(List<Jsp.Content> nodes, Set<String> elVisible) {
        List<Jsp.Content> out = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Jsp.Content node = nodes.get(i);
            if (node instanceof Jsp.Scriptlet) {
                int consumed = convertBlock(nodes, i, elVisible, out);
                if (consumed > 0) {
                    i += consumed - 1;
                    continue;
                }
                out.add(node);
            } else if (node instanceof Jsp.ExpressionScriptlet && convertOutputs) {
                out.add(convertOutput((Jsp.ExpressionScriptlet) node, elVisible));
            } else if (node instanceof Jsp.Tag && ((Jsp.Tag) node).getBody() != null) {
                Jsp.Tag tag = (Jsp.Tag) node;
                out.add(tag.withBody(convert(tag.getBody(), elVisible)));
            } else {
                out.add(node);
            }
        }
        return out;
    }

    private Jsp.Content convertOutput(Jsp.ExpressionScriptlet expression, Set<String> elVisible) {
        JavaToEl.Result el = translate(expression.getCodeSource(), elVisible);
        if (!el.ok()) {
            conversions.add(new Conversion("expression", false, el.failure(), expression));
            return expression;
        }
        conversions.add(new Conversion("expression", true, "${" + el.el() + "}", expression));
        if (outputsWithCOut) {
            usedCore = true;
            return tag("out", List.of(attribute("value", "${" + el.el() + "}"), attribute("escapeXml", "false")), null);
        }
        return new Jsp.ExpressionLanguage(randomId(), "", Markers.EMPTY, Jsp.ExpressionLanguage.Type.IMMEDIATE,
                el.el());
    }

    /** An opener: statements before it, the keyword, its header, and code after the body's {. */
    private record Opener(String prefix, String keyword, String header, String afterBrace) {
    }

    /** A closer: what follows the }: an else, an else if (header), or plain statements. */
    private record Closer(@Nullable String elseIfHeader, boolean isElse, String suffix) {
    }

    /**
     * Tries to convert the block opened by the scriptlet at {@code start}.
     *
     * @return how many nodes were consumed (0 if not converted).
     */
    private int convertBlock(List<Jsp.Content> nodes, int start, Set<String> elVisible, List<Jsp.Content> out) {
        Jsp.Scriptlet first = (Jsp.Scriptlet) nodes.get(start);
        Opener opener = opener(first.getCodeSource());
        if (opener == null) {
            return 0;
        }
        String construct = "if".equals(opener.keyword()) ? "if" : "for";
        if ("if".equals(opener.keyword()) && !opener.afterBrace().isBlank()) {
            conversions.add(new Conversion(construct, false, "its opening scriptlet has code after the {", first));
            return 0;
        }

        // Segments: [header, body nodes] for if/else-if/else; closers between them.
        List<String> headers = new ArrayList<>();
        List<List<Jsp.Content>> bodies = new ArrayList<>();
        headers.add(opener.header());
        int from = start + 1;
        Closer closer;
        int closerIndex;
        while (true) {
            closerIndex = findCloser(nodes, from);
            if (closerIndex < 0) {
                conversions.add(new Conversion(construct, false,
                        "its closing } isn't in a scriptlet of its own at the same level (the block crosses a tag " +
                        "boundary, or other code is mixed in)", first));
                return 0;
            }
            bodies.add(new ArrayList<>(nodes.subList(from, closerIndex)));
            closer = closer(((Jsp.Scriptlet) nodes.get(closerIndex)).getCodeSource());
            if (closer == null) {
                conversions.add(new Conversion(construct, false, "its closing scriptlet has code that can't be split off",
                        first));
                return 0;
            }
            if (closer.elseIfHeader() == null && !closer.isElse()) {
                break;
            }
            if (!"if".equals(opener.keyword())) {
                return 0;
            }
            construct = "if/else";
            headers.add(closer.isElse() ? null : closer.elseIfHeader());
            from = closerIndex + 1;
        }

        List<Jsp.Content> replacement = "if".equals(opener.keyword()) ?
                convertIf(headers, bodies, elVisible, first) :
                convertFor(opener, bodies.get(0), elVisible, first);
        if (replacement == null) {
            return 0;
        }
        if (!opener.prefix().isBlank()) {
            out.add(new Jsp.Scriptlet(randomId(), "", Markers.EMPTY, opener.prefix()));
        }
        out.addAll(replacement);
        if (!closer.suffix().isBlank()) {
            out.add(new Jsp.Scriptlet(randomId(), "", Markers.EMPTY, closer.suffix()));
        }
        usedCore = true;
        return closerIndex - start + 1;
    }

    private @Nullable List<Jsp.Content> convertIf(List<String> headers, List<List<Jsp.Content>> bodies,
                                                  Set<String> elVisible, Jsp.Scriptlet first) {
        List<String> tests = new ArrayList<>();
        for (String header : headers) {
            if (header == null) {
                tests.add(null);
                continue;
            }
            JavaToEl.Result el = translate(header, elVisible);
            if (!el.ok()) {
                conversions.add(new Conversion(headers.size() > 1 ? "if/else" : "if", false,
                        "condition '" + header.trim() + "': " + el.failure(), first));
                return null;
            }
            tests.add("${" + el.el() + "}");
        }
        conversions.add(new Conversion(headers.size() > 1 ? "if/else" : "if", true, String.join(" / ",
                tests.stream().map(t -> t == null ? "otherwise" : t).toList()), first));
        if (tests.size() == 1) {
            return List.of(tag("if", List.of(attribute("test", tests.get(0))), convert(bodies.get(0), elVisible)));
        }
        List<Jsp.Content> branches = new ArrayList<>();
        for (int i = 0; i < tests.size(); i++) {
            List<Jsp.Content> body = convert(bodies.get(i), elVisible);
            branches.add(tests.get(i) == null ? tag("otherwise", List.of(), body) :
                    tag("when", List.of(attribute("test", tests.get(i))), body));
        }
        return List.of(tag("choose", List.of(), branches));
    }

    private @Nullable List<Jsp.Content> convertFor(Opener opener, List<Jsp.Content> body, Set<String> elVisible,
                                                   Jsp.Scriptlet first) {
        String header = opener.header();
        String variable;
        List<Jsp.Attribute> attributes = new ArrayList<>();
        String construct;
        Matcher forEach = FOR_EACH_HEADER.matcher(header);
        Matcher count = FOR_COUNT_HEADER.matcher(header);
        if (forEach.matches()) {
            construct = "for-each";
            variable = forEach.group(1);
            JavaToEl.Result items = translate(forEach.group(2), elVisible);
            if (!items.ok()) {
                conversions.add(new Conversion(construct, false, "items '" + forEach.group(2).trim() + "': " +
                                                                 items.failure(), first));
                return null;
            }
            attributes.add(attribute("var", variable));
            attributes.add(attribute("items", "${" + items.el() + "}"));
        } else if (count.matches()) {
            construct = "for";
            variable = count.group(1);
            String updated = count.group(6) != null ? count.group(6) : count.group(7) != null ? count.group(7) :
                    count.group(8);
            if (!variable.equals(count.group(3)) || !variable.equals(updated)) {
                conversions.add(new Conversion(construct, false, "only simple counting loops (int i = a; i < b; i++) " +
                                                                 "are converted", first));
                return null;
            }
            JavaToEl.Result begin = translate(count.group(2), elVisible);
            JavaToEl.Result end = translate(count.group(5), elVisible);
            if (!begin.ok() || !end.ok()) {
                conversions.add(new Conversion(construct, false, "bounds: " +
                                                                 (begin.ok() ? end.failure() : begin.failure()), first));
                return null;
            }
            boolean inclusive = "<=".equals(count.group(4));
            attributes.add(attribute("var", variable));
            attributes.add(attribute("begin", literalOrEl(begin.el())));
            attributes.add(attribute("end", inclusive ? literalOrEl(end.el()) : "${" + end.el() + " - 1}"));
        } else {
            conversions.add(new Conversion("for", false, "only for-each loops and simple counting loops " +
                                                         "(int i = a; i < b; i++) are converted", first));
            return null;
        }
        if (!opener.afterBrace().isBlank() &&
            !opener.afterBrace().matches("\\s*pageContext\\s*\\.\\s*setAttribute\\s*\\(\\s*\"" + variable +
                                         "\"\\s*,\\s*" + variable + "\\s*\\)\\s*;\\s*")) {
            conversions.add(new Conversion(construct, false, "its opening scriptlet has code after the {", first));
            return null;
        }

        Set<String> inBody = new HashSet<>(elVisible);
        inBody.add(variable);
        int before = conversions.size();
        List<Jsp.Content> converted = convert(body, inBody);
        if (javaReferences(converted, variable)) {
            // The loop variable is still needed as a Java variable: keep the loop, and redo the
            // body without assuming EL can see it.
            conversions.subList(before, conversions.size()).clear();
            conversions.add(new Conversion(construct, false, "Java code in its body still uses '" + variable + "'",
                    first));
            return null;
        }
        conversions.add(before, new Conversion(construct, true, attributes.stream()
                .map(a -> a.getName() + "=\"" + a.getValue().getValue() + "\"")
                .reduce((a, b) -> a + " " + b).orElse(""), first));
        return List.of(tag("forEach", attributes, converted));
    }

    // -----------------------------------------------------------------------------------------
    // Assignments to c:set
    // -----------------------------------------------------------------------------------------

    /**
     * Replaces scriptlets that only assign a mirrored variable ({@code x = expr;} plus its
     * {@code setAttribute}) with {@code <c:set>}, for variables no other Java code uses anymore.
     */
    List<Jsp.Content> convertAssignments(List<Jsp.Content> nodes, Set<String> elVisible) {
        Map<String, List<Jsp.Scriptlet>> candidates = new LinkedHashMap<>();
        collectAssignments(nodes, candidates);
        Map<UUID, Jsp.Content> replacements = new LinkedHashMap<>();
        for (Map.Entry<String, List<Jsp.Scriptlet>> entry : candidates.entrySet()) {
            String name = entry.getKey();
            if (!mirrored.contains(name)) {
                continue;
            }
            Set<UUID> own = new HashSet<>();
            entry.getValue().forEach(s -> own.add(s.getId()));
            if (javaReferencesOutside(nodes, name, own)) {
                for (Jsp.Scriptlet s : entry.getValue()) {
                    conversions.add(new Conversion("set", false, "other Java code still uses '" + name + "'", s));
                }
                continue;
            }
            Map<UUID, Jsp.Content> sets = new LinkedHashMap<>();
            String failure = null;
            for (Jsp.Scriptlet scriptlet : entry.getValue()) {
                Matcher m = ASSIGNMENT_SCRIPTLET.matcher(scriptlet.getCodeSource());
                if (!m.matches()) {
                    continue;
                }
                JavaToEl.Result value = translate(m.group(2), elVisible);
                if (!value.ok()) {
                    failure = "value '" + m.group(2).trim() + "': " + value.failure();
                    break;
                }
                sets.put(scriptlet.getId(), tag("set", List.of(attribute("var", name),
                        attribute("value", "${" + value.el() + "}")), null));
            }
            for (Jsp.Scriptlet s : entry.getValue()) {
                conversions.add(new Conversion("set", failure == null, failure == null ? "var=\"" + name + "\"" : failure, s));
            }
            if (failure == null) {
                replacements.putAll(sets);
                usedCore = true;
            }
        }
        return replacements.isEmpty() ? nodes : replace(nodes, replacements);
    }

    private static void collectAssignments(List<Jsp.Content> nodes, Map<String, List<Jsp.Scriptlet>> candidates) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Scriptlet) {
                Matcher m = ASSIGNMENT_SCRIPTLET.matcher(((Jsp.Scriptlet) node).getCodeSource());
                if (m.matches() && (m.group(3) == null || (m.group(3).equals(m.group(1)) && m.group(4).equals(m.group(1))))) {
                    candidates.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add((Jsp.Scriptlet) node);
                }
            } else if (node instanceof Jsp.Tag && ((Jsp.Tag) node).getBody() != null) {
                collectAssignments(((Jsp.Tag) node).getBody(), candidates);
            }
        }
    }

    private static List<Jsp.Content> replace(List<Jsp.Content> nodes, Map<UUID, Jsp.Content> replacements) {
        List<Jsp.Content> out = new ArrayList<>(nodes.size());
        for (Jsp.Content node : nodes) {
            Jsp.Content replacement = replacements.get(node.getId());
            if (replacement != null) {
                out.add(replacement);
            } else if (node instanceof Jsp.Tag && ((Jsp.Tag) node).getBody() != null) {
                out.add(((Jsp.Tag) node).withBody(replace(((Jsp.Tag) node).getBody(), replacements)));
            } else {
                out.add(node);
            }
        }
        return out;
    }

    // -----------------------------------------------------------------------------------------
    // Parsing openers and closers
    // -----------------------------------------------------------------------------------------

    private static @Nullable Opener opener(String code) {
        String masked = JspVariables.maskLiteralsAndComments(code);
        Matcher keyword = KEYWORD_AT.matcher(masked);
        while (keyword.find()) {
            int at = keyword.start();
            if (at > 0 && (Character.isJavaIdentifierPart(masked.charAt(at - 1)) || masked.charAt(at - 1) == '.')) {
                continue;
            }
            String prefix = masked.substring(0, at);
            if (!prefix.isBlank() && !prefix.stripTrailing().endsWith(";") && !prefix.stripTrailing().endsWith("}")) {
                continue;
            }
            int[] prefixBraces = JspPageAnalyzer.braces(prefix);
            if (prefixBraces[0] != 0 || prefixBraces[1] != 0) {
                continue;
            }
            int open = masked.indexOf('(', at);
            int close = matchingParen(masked, open);
            if (close < 0) {
                continue;
            }
            int brace = skipWhitespace(masked, close + 1);
            if (brace >= masked.length() || masked.charAt(brace) != '{') {
                continue;
            }
            String after = code.substring(brace + 1);
            int[] afterBraces = JspPageAnalyzer.braces(JspVariables.maskLiteralsAndComments(after));
            if (afterBraces[0] != 0 || afterBraces[1] != 0) {
                continue;
            }
            return new Opener(code.substring(0, at), keyword.group(1), code.substring(open + 1, close), after);
        }
        return null;
    }

    private static @Nullable Closer closer(String code) {
        String masked = JspVariables.maskLiteralsAndComments(code);
        int brace = skipWhitespace(masked, 0);
        if (brace >= masked.length() || masked.charAt(brace) != '}') {
            return null;
        }
        String rest = masked.substring(brace + 1);
        Matcher elseIf = ELSE_IF.matcher(rest);
        if (elseIf.lookingAt()) {
            int open = brace + 1 + elseIf.end() - 1;
            int close = matchingParen(masked, open);
            if (close < 0) {
                return null;
            }
            int body = skipWhitespace(masked, close + 1);
            if (body >= masked.length() || masked.charAt(body) != '{' || !masked.substring(body + 1).isBlank()) {
                return null;
            }
            return new Closer(code.substring(open + 1, close), false, "");
        }
        if (ELSE.matcher(rest).matches()) {
            return new Closer(null, true, "");
        }
        if (rest.contains("else")) {
            return null;
        }
        int[] suffixBraces = JspPageAnalyzer.braces(rest);
        if (suffixBraces[0] != 0 || suffixBraces[1] != 0) {
            return null;
        }
        return new Closer(null, false, code.substring(brace + 1));
    }

    /**
     * @return the index of the sibling scriptlet whose leading <code>}</code> closes the block opened
     * just before {@code from}, or {@code -1}.
     */
    private static int findCloser(List<Jsp.Content> nodes, int from) {
        int depth = 1;
        for (int i = from; i < nodes.size(); i++) {
            Jsp.Content node = nodes.get(i);
            if (node instanceof Jsp.Scriptlet) {
                String masked = JspVariables.maskLiteralsAndComments(((Jsp.Scriptlet) node).getCodeSource());
                int[] braces = JspPageAnalyzer.braces(masked);
                if (braces[0] >= depth) {
                    return depth == 1 && masked.strip().startsWith("}") ? i : -1;
                }
                depth = depth - braces[0] + braces[1];
            } else if (node instanceof Jsp.Tag && ((Jsp.Tag) node).getBody() != null &&
                       netBraces(((Jsp.Tag) node).getBody()) != 0) {
                return -1; // a Java block crossing the tag's boundary
            }
        }
        return -1;
    }

    private static int netBraces(List<Jsp.Content> nodes) {
        int net = 0;
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Scriptlet) {
                int[] braces = JspPageAnalyzer.braces(JspVariables.maskLiteralsAndComments(
                        ((Jsp.Scriptlet) node).getCodeSource()));
                net += braces[1] - braces[0];
            } else if (node instanceof Jsp.Tag && ((Jsp.Tag) node).getBody() != null) {
                net += netBraces(((Jsp.Tag) node).getBody());
            }
        }
        return net;
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private JavaToEl.Result translate(String java, Set<String> elVisible) {
        JavaToEl.Result result = JavaToEl.translate(java, elVisible);
        if (!result.ok()) {
            return result;
        }
        if (result.usesFunctions()) {
            if (functionsPrefix == null) {
                return new JavaToEl.Result(null, false, "needs the JSTL functions library, which JSTL 1.0 doesn't have");
            }
            usedFunctions = true;
            String el = functionsPrefix.equals("fn") ? result.el() : result.el().replace("fn:length(",
                    functionsPrefix + ":length(");
            return new JavaToEl.Result(el, true, null);
        }
        if (result.el().contains("\"")) {
            return new JavaToEl.Result(null, false, "a double quote in a string can't go in a tag attribute");
        }
        return result;
    }

    private static boolean javaReferences(List<Jsp.Content> nodes, String name) {
        return javaReferencesOutside(nodes, name, Set.of());
    }

    private static boolean javaReferencesOutside(List<Jsp.Content> nodes, String name, Set<UUID> except) {
        Pattern reference = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "(?![\\w$])");
        for (Jsp.Content node : nodes) {
            if (except.contains(node.getId())) {
                continue;
            }
            String code = null;
            if (node instanceof Jsp.Scriptlet) {
                code = ((Jsp.Scriptlet) node).getCodeSource();
            } else if (node instanceof Jsp.ExpressionScriptlet) {
                code = ((Jsp.ExpressionScriptlet) node).getCodeSource();
            } else if (node instanceof Jsp.Declaration) {
                code = ((Jsp.Declaration) node).getCodeSource();
            } else if (node instanceof Jsp.Tag) {
                for (Jsp.Attribute attribute : ((Jsp.Tag) node).getAttributes()) {
                    for (String expression : JspVariables.expressionsInText(attribute.getValue().getValue())) {
                        if (reference.matcher(JspVariables.maskLiteralsAndComments(expression)).find()) {
                            return true;
                        }
                    }
                }
                if (((Jsp.Tag) node).getBody() != null &&
                    javaReferencesOutside(((Jsp.Tag) node).getBody(), name, except)) {
                    return true;
                }
            }
            if (code != null && reference.matcher(JspVariables.maskLiteralsAndComments(code)).find()) {
                return true;
            }
        }
        return false;
    }

    private Jsp.Tag tag(String localName, List<Jsp.Attribute> attributes, @Nullable List<Jsp.Content> body) {
        String name = corePrefix + ":" + localName;
        if (body == null) {
            return new Jsp.Tag(randomId(), "", Markers.EMPTY, name, attributes, true, "", null, null);
        }
        return new Jsp.Tag(randomId(), "", Markers.EMPTY, name, attributes, false, "", body,
                new Jsp.Tag.Closing(randomId(), "", Markers.EMPTY, name, ""));
    }

    private static Jsp.Attribute attribute(String name, String value) {
        return new Jsp.Attribute(randomId(), " ", Markers.EMPTY, name, "",
                new Jsp.Attribute.Value(randomId(), "", Markers.EMPTY, '"', value));
    }

    private static String literalOrEl(String el) {
        return el.matches("-?\\d+") ? el : "${" + el + "}";
    }

    private static int matchingParen(String masked, int open) {
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
