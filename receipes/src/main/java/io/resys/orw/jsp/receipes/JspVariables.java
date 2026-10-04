package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.tree.Jsp;
import io.resys.orw.jsp.tree.TagLibrary;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-level (regex-based, not a real Java or EL parser) extraction of variable definitions and
 * uses from JSP Java code and EL expressions, for {@link JspPageAnalyzer}'s
 * {@code VARIABLE_FROM_INCLUDE} rule.
 */
final class JspVariables {

    enum Kind {
        /**
         * A local variable declared in a scriptlet; visible to later Java code.
         */
        JAVA_VARIABLE("Java variable"),
        /**
         * A field declared in a {@code <%! %>} declaration; visible to all Java code.
         */
        JAVA_FIELD("Java field declared in <%! %>"),
        /**
         * A method declared in a {@code <%! %>} declaration.
         */
        JAVA_METHOD("Java method declared in <%! %>"),
        /**
         * A scoped attribute ({@code <c:set var>}, {@code request.setAttribute(...)}, ...); visible to EL.
         */
        SCOPED_ATTRIBUTE("scoped attribute"),
        /**
         * A {@code <jsp:useBean>}: both a Java variable and a scoped attribute.
         */
        BEAN("bean declared with <jsp:useBean>"),
        /**
         * A scripting variable a custom tag declares in its TLD ({@code <variable>}); tag handlers
         * conventionally also store it as a page attribute, so it's visible to EL too.
         */
        TAG_VARIABLE("variable declared by a tag's TLD");

        final String description;

        Kind(String description) {
            this.description = description;
        }

        boolean visibleToJava() {
            return this != SCOPED_ATTRIBUTE;
        }

        boolean visibleToEl() {
            return this == SCOPED_ATTRIBUTE || this == BEAN || this == TAG_VARIABLE;
        }
    }

    record Definition(String name, Kind kind, int offset) {
    }

    record Use(String name, boolean call, boolean scoped) {
    }

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "break", "case", "catch", "class", "const", "continue", "default", "do",
            "else", "enum", "extends", "finally", "for", "goto", "if", "implements", "import", "instanceof",
            "interface", "native", "new", "package", "return", "strictfp", "super", "switch", "synchronized",
            "this", "throw", "throws", "try", "while", "yield", "true", "false", "null");

    private static final Set<String> EL_RESERVED = Set.of(
            "and", "or", "not", "eq", "ne", "lt", "gt", "le", "ge", "true", "false", "null", "instanceof",
            "empty", "div", "mod",
            // Implicit objects
            "pageContext", "pageScope", "requestScope", "sessionScope", "applicationScope", "param",
            "paramValues", "header", "headerValues", "cookie", "initParam");

    /**
     * Tags whose {@code var} only exists inside their own body, so it never leaks out of a file.
     */
    private static final Pattern BODY_SCOPED_VAR_TAG = Pattern.compile("(?:^|:)(?:forEach|forTokens)$");

    private static final String TYPE =
            "(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*[A-Za-z_$][\\w$]*(?:\\s*<[^;=(){}]*>)?(?:\\s*\\[\\s*\\])*";

    private static final Pattern DECLARATION = Pattern.compile(
            "(?:^|(?<=[;{}]))\\s*(?:@[\\w.]+\\s+)*" +
            "(?:(?:final|static|private|protected|public|transient|volatile|synchronized)\\s+)*" +
            "(" + TYPE + ")\\s+([A-Za-z_$][\\w$]*)\\s*([=;,(]|$)");

    private static final Pattern IDENTIFIER = Pattern.compile("(?<![\\w$.])([A-Za-z_$][\\w$]*)(\\s*\\()?");

    private static final Pattern SET_ATTRIBUTE = Pattern.compile(
            "\\b(?:pageContext|request|session|application)\\s*\\.\\s*setAttribute\\s*\\(\\s*\"([^\"]+)\"");

    private static final Pattern GET_ATTRIBUTE = Pattern.compile(
            "\\b(?:pageContext|request|session|application)\\s*\\.\\s*(?:get|find)Attribute\\s*\\(\\s*\"([^\"]+)\"");

    private static final Pattern EL_SCOPE_ACCESS = Pattern.compile(
            "\\b(?:pageScope|requestScope|sessionScope|applicationScope)\\s*" +
            "(?:\\.\\s*([A-Za-z_$][\\w$]*)|\\[\\s*['\"]([^'\"]+)['\"]\\s*])");

    private static final Pattern EL_IDENTIFIER = Pattern.compile("(?<![\\w$.:])([A-Za-z_$][\\w$]*)(?!\\s*\\(|:[A-Za-z_]|[\\w$])");

    private static final Pattern EL_IN_TEXT = Pattern.compile("[$#]\\{((?:[^}'\"]|'[^']*'|\"[^\"]*\")*)}");

    private static final Pattern EXPRESSION_IN_TEXT = Pattern.compile("<%=(.*?)%>", Pattern.DOTALL);

    private JspVariables() {
    }

    /**
     * Variables (and, in a {@code <%! %>} declaration, fields and methods) declared at the top level
     * of the code, i.e. not inside a block, where they would not be visible to later code.
     *
     * @param initialDepth how many Java blocks are already open where the code starts.
     */
    static List<Definition> javaDeclarations(String code, boolean inDeclaration, int initialDepth) {
        String masked = maskNested(code, initialDepth);
        List<Definition> definitions = new ArrayList<>();
        Matcher m = DECLARATION.matcher(masked);
        while (m.find()) {
            String type = m.group(1).replaceAll("\\s*<.*", "").replaceAll("\\s*\\[.*", "");
            String name = m.group(2);
            String next = m.group(3);
            if (JAVA_KEYWORDS.contains(type) || JAVA_KEYWORDS.contains(name)) {
                continue;
            }
            if ("(".equals(next)) {
                if (inDeclaration) {
                    definitions.add(new Definition(name, Kind.JAVA_METHOD, m.start(2)));
                }
                continue;
            }
            Kind kind = inDeclaration ? Kind.JAVA_FIELD : Kind.JAVA_VARIABLE;
            definitions.add(new Definition(name, kind, m.start(2)));
            if (",".equals(next) || "=".equals(next)) {
                // Further declarators in the same statement: "int a = 1, b, c = 2;".
                int end = masked.indexOf(';', m.end(3));
                String rest = masked.substring(m.end(3) - 1, end < 0 ? masked.length() : end);
                Matcher more = Pattern.compile(",\\s*([A-Za-z_$][\\w$]*)\\s*(?=[=,]|$)").matcher(rest);
                while (more.find()) {
                    definitions.add(new Definition(more.group(1), kind, m.end(3) - 1 + more.start(1)));
                }
            }
        }
        Matcher set = SET_ATTRIBUTE.matcher(code);
        while (set.find()) {
            definitions.add(new Definition(set.group(1), Kind.SCOPED_ATTRIBUTE, set.start(1)));
        }
        return definitions;
    }

    /**
     * @return the variable a custom/standard action defines for the rest of the page, if any:
     * {@code var} on e.g. {@code <c:set>}/{@code <c:url>}/{@code <fmt:message>} (but not on an
     * iteration tag, whose variable only exists in its body), or {@code id} on {@code <jsp:useBean>}.
     */
    static @Nullable Definition tagDefinition(String tagName, String attributeName, String value) {
        if (value.isEmpty() || value.contains("${") || value.contains("#{") || value.contains("<%")) {
            return null;
        }
        if ("jsp:useBean".equals(tagName) && "id".equals(attributeName)) {
            return new Definition(value, Kind.BEAN, 0);
        }
        if ("var".equals(attributeName) && !BODY_SCOPED_VAR_TAG.matcher(tagName).find()) {
            return new Definition(value, Kind.SCOPED_ATTRIBUTE, 0);
        }
        return null;
    }

    /**
     * @return the variables a tag's TLD declares that outlive the tag ({@code AT_BEGIN} or
     * {@code AT_END}; {@code NESTED} ones only exist in its body), named either by the TLD or by
     * the value of the attribute the TLD names.
     */
    static List<Definition> tldDefinitions(Jsp.Tag tag, TagLibrary.TagDescriptor descriptor) {
        List<Definition> definitions = new ArrayList<>();
        for (TagLibrary.VariableDescriptor variable : descriptor.getVariables()) {
            if ("NESTED".equalsIgnoreCase(variable.getScope())) {
                continue;
            }
            String name = variable.getNameGiven();
            if (name == null && variable.getNameFromAttribute() != null) {
                for (Jsp.Attribute attribute : tag.getAttributes()) {
                    if (attribute.getName().equals(variable.getNameFromAttribute())) {
                        name = attribute.getValue().getValue();
                    }
                }
            }
            if (name != null && !name.isEmpty() && !name.contains("${") && !name.contains("<%")) {
                definitions.add(new Definition(name, Kind.TAG_VARIABLE, 0));
            }
        }
        return definitions;
    }

    /**
     * @return identifiers referenced by the Java code (not as a member of something else, i.e. not
     * preceded by {@code .}), noting whether each is called as a method.
     */
    static List<Use> javaUses(String code) {
        String masked = maskLiteralsAndComments(code);
        List<Use> uses = new ArrayList<>();
        Matcher m = IDENTIFIER.matcher(masked);
        while (m.find()) {
            if (!JAVA_KEYWORDS.contains(m.group(1))) {
                uses.add(new Use(m.group(1), m.group(2) != null, false));
            }
        }
        Matcher get = GET_ATTRIBUTE.matcher(code);
        while (get.find()) {
            uses.add(new Use(get.group(1), false, true));
        }
        return uses;
    }

    /**
     * @return the top-level names an EL expression body refers to, e.g. {@code user} for
     * {@code user.name}, plus names accessed through a scope map, e.g. {@code x} for
     * {@code requestScope.x}.
     */
    static List<String> elUses(String expression) {
        String masked = expression.replaceAll("'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"", "''");
        List<String> uses = new ArrayList<>();
        Matcher scope = EL_SCOPE_ACCESS.matcher(expression);
        while (scope.find()) {
            uses.add(scope.group(1) != null ? scope.group(1) : scope.group(2));
        }
        Matcher m = EL_IDENTIFIER.matcher(masked);
        while (m.find()) {
            if (!EL_RESERVED.contains(m.group(1))) {
                uses.add(m.group(1));
            }
        }
        return uses;
    }

    /**
     * @return the bodies of the EL expressions embedded in raw text (e.g. an attribute value).
     */
    static List<String> elInText(String text) {
        List<String> bodies = new ArrayList<>();
        Matcher m = EL_IN_TEXT.matcher(text);
        while (m.find()) {
            bodies.add(m.group(1));
        }
        return bodies;
    }

    /**
     * @return the code of the {@code <%= %>} expressions embedded in raw text (e.g. an attribute value).
     */
    static List<String> expressionsInText(String text) {
        List<String> codes = new ArrayList<>();
        Matcher m = EXPRESSION_IN_TEXT.matcher(text);
        while (m.find()) {
            codes.add(m.group(1));
        }
        return codes;
    }

    /**
     * Blanks out string/char literals and comments, keeping offsets (and newlines) intact.
     */
    static String maskLiteralsAndComments(String code) {
        return mask(code, Integer.MAX_VALUE);
    }

    /**
     * Like {@link #maskLiteralsAndComments}, but also blanks out everything nested in {@code ()},
     * {@code {}} or {@code []} (keeping the outermost brackets themselves), so only top-level
     * statements remain readable.
     */
    private static String maskNested(String code, int initialDepth) {
        return mask(code, initialDepth);
    }

    /**
     * @param initialDepth the nesting depth at the start; {@link Integer#MAX_VALUE} disables
     *                     masking of nested content.
     */
    private static String mask(String code, int initialDepth) {
        boolean maskNested = initialDepth != Integer.MAX_VALUE;
        int depth = maskNested ? initialDepth : 0;
        StringBuilder out = new StringBuilder(code.length());
        int n = code.length();
        for (int i = 0; i < n; i++) {
            char c = code.charAt(i);
            int start = i;
            if (c == '"' || c == '\'') {
                for (i++; i < n && code.charAt(i) != c && code.charAt(i) != '\n'; i++) {
                    if (code.charAt(i) == '\\') {
                        i++;
                    }
                }
                blank(code, start, Math.min(i, n - 1), out);
                continue;
            }
            if (c == '/' && i + 1 < n && (code.charAt(i + 1) == '/' || code.charAt(i + 1) == '*')) {
                int end = code.charAt(i + 1) == '/' ? code.indexOf('\n', i) : code.indexOf("*/", i + 2);
                i = end < 0 ? n - 1 : (code.charAt(start + 1) == '/' ? end - 1 : end + 1);
                blank(code, start, i, out);
                continue;
            }
            if (maskNested && (c == '(' || c == '{' || c == '[')) {
                out.append(depth == 0 ? c : ' ');
                depth++;
            } else if (maskNested && (c == ')' || c == '}' || c == ']')) {
                depth = Math.max(0, depth - 1);
                out.append(depth == 0 ? c : ' ');
            } else {
                out.append(maskNested && depth > 0 && c != '\n' ? ' ' : c);
            }
        }
        return out.toString();
    }

    private static void blank(String code, int from, int to, StringBuilder out) {
        for (int j = from; j <= to; j++) {
            out.append(code.charAt(j) == '\n' ? '\n' : ' ');
        }
    }
}
