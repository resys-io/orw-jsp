package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.internal.JspPrinter;
import io.resys.openrewrite.jsp.tree.Jsp;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ParseWarning;
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.marker.Markers;
import org.openrewrite.marker.SearchResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.openrewrite.Tree.randomId;

/**
 * Analyzes one JSP page for the problems {@link FindJspProblems} reports.
 * <p>
 * The parser keeps all plain HTML as {@link Jsp.Text}, so HTML structure is recovered here: the
 * text nodes are scanned in document order by a small HTML tokenizer whose state carries across
 * the JSP constructs between them. That's what lets it notice a scriptlet sitting <em>inside</em>
 * an HTML start tag, which no single node shows. Statically included files are scanned in place
 * (read-only, as {@link Jsp.Directive#getIncludedFile()}), so a {@code <div>} opened in a header
 * include and closed in a footer include balances as it does at runtime.
 * <p>
 * Open HTML elements are kept on a stack, interleaved with <em>block barriers</em>: the body of a
 * JSP action (e.g. {@code <c:if>}) and each Java block ({@code { ... }}) opened by a scriptlet. An
 * element opened inside a block must be closed inside it, otherwise the page's markup structure
 * depends on which branch runs ({@link Rule#ELEMENT_CROSSES_BLOCK}).
 */
final class JspPageAnalyzer {

    enum Rule {
        MISSING_END_TAG(true),
        UNMATCHED_END_TAG(true),
        ELEMENT_CROSSES_BLOCK(true),
        TAG_SPLIT_ACROSS_FILES(true),
        END_TAG_WITH_ATTRIBUTES(false),
        SCRIPTLET_IN_HTML_TAG(false),
        GENERATED_ATTRIBUTE(false),
        HTML_IN_SCRIPTLET(false),
        LARGE_SCRIPTLET(false),
        JSP_IN_HTML_COMMENT(false),
        VARIABLE_FROM_INCLUDE(false),
        UNRESOLVED_INCLUDE(false);

        /**
         * Whether the rule is about tag balance, which can't be judged without the whole page
         * (so it is skipped when an include is unresolved).
         */
        final boolean balance;

        Rule(boolean balance) {
            this.balance = balance;
        }
    }

    record Problem(Rule rule, Path file, int line, String message) {
    }

    /**
     * Elements whose end tag is mandatory in HTML is the default; these may omit it.
     */
    private static final Set<String> OPTIONAL_END_TAG = Set.of(
            "html", "head", "body", "li", "dt", "dd", "p", "rt", "rp", "optgroup", "option",
            "colgroup", "caption", "thead", "tbody", "tfoot", "tr", "td", "th");

    /**
     * Elements that never have an end tag (or content).
     */
    private static final Set<String> VOID = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param",
            "source", "track", "wbr", "basefont", "frame", "isindex", "keygen", "command");

    /**
     * Elements whose content is raw text, not markup, up to their own end tag.
     */
    private static final Set<String> RAW_TEXT = Set.of("script", "style", "textarea", "title", "xmp");

    /**
     * {@code out.print("<div ...")} and friends: markup written from Java string literals.
     */
    private static final Pattern HTML_IN_JAVA = Pattern.compile(
            "\\bout\\s*\\.\\s*(?:print|println|write|append)\\s*\\(\\s*\"(?:[^\"\\\\]|\\\\.)*<\\s*/?\\s*[A-Za-z]");

    private enum State {
        DATA, IN_TAG, ATTR_VALUE, COMMENT, BOGUS_COMMENT, RAW_TEXT
    }

    /**
     * Where a problem is reported: a node, or (for a {@link Jsp.Text}) an offset into it.
     */
    private record Anchor(UUID nodeId, int offset) {
    }

    private record Location(Path file, int line, Anchor anchor) {
    }

    private static final class Element {
        final String name;
        final Location location;
        /**
         * Already reported as crossing a block boundary; not to be reported again as missing.
         */
        boolean reported;

        Element(String name, Location location) {
            this.name = name;
            this.location = location;
        }
    }

    private record Barrier(String description, Location location, boolean javaBlock) {
    }

    private final Path pageFile;
    private final boolean fragment;
    private final int maxScriptletLines;
    private final boolean allowSplitBetweenIncludedFiles;
    private final boolean balanceChecked;

    private final List<Problem> problems = new ArrayList<>();
    private final Map<UUID, TreeMap<Integer, List<String>>> messagesByAnchor = new HashMap<>();
    private final List<Object> stack = new ArrayList<>();

    // Position in the file currently being scanned.
    private Path file;
    private int line = 1;
    private @Nullable UUID includeAnchor;

    // HTML tokenizer state, carried across nodes.
    private State state = State.DATA;
    private String tagName = "";
    private boolean endTag;
    private boolean endTagHasContent;
    private boolean closesRawText;
    private boolean expectingValue;
    private boolean inUnquotedValue;
    private char lastNonWhitespace;
    private char quote;
    private String rawTextElement = "";
    private @Nullable Location tagLocation;
    private @Nullable Location commentLocation;
    private boolean commentReported;

    // Variables defined in included files, by the name the page would use them by.
    private record IncludedDefinition(JspVariables.Kind kind, Location location) {
    }

    private final Map<String, IncludedDefinition> includedJavaVariables = new HashMap<>();
    private final Map<String, IncludedDefinition> includedJavaMethods = new HashMap<>();
    private final Map<String, IncludedDefinition> includedScopedVariables = new HashMap<>();
    private final Set<String> reportedVariables = new HashSet<>();
    private int javaDepth;

    /**
     * @param fragment whether the page is a fragment (a {@code .jspf}, or a file other pages
     *                 include), whose markup may legitimately be closed or opened by its includers.
     */
    JspPageAnalyzer(Jsp.Document document, boolean fragment, int maxScriptletLines,
                    boolean allowSplitBetweenIncludedFiles) {
        this.allowSplitBetweenIncludedFiles = allowSplitBetweenIncludedFiles;
        this.pageFile = document.getSourcePath();
        this.file = pageFile;
        this.fragment = fragment;
        this.maxScriptletLines = maxScriptletLines;
        this.balanceChecked = UnresolvedIncludes.find(document).isEmpty();
    }

    List<Problem> analyze(Jsp.Document document) {
        walk(document.getNodes());
        for (Object entry : stack) {
            if (entry instanceof Element) {
                Element element = (Element) entry;
                if (!element.reported && requiresEndTag(element.name)) {
                    report(Rule.MISSING_END_TAG, element.location, null, true,
                            "<" + element.name + "> has no end tag");
                }
            }
        }
        return problems;
    }

    /**
     * @return the document with a {@link SearchResult} marker at every problem, splitting text
     * nodes where needed so that each marker sits right where its problem starts. Printing the
     * result without markers reproduces the original source.
     */
    Jsp.Document annotate(Jsp.Document document) {
        if (messagesByAnchor.isEmpty()) {
            return document;
        }
        return document.withNodes(annotate(document.getNodes()));
    }

    private List<Jsp.Content> annotate(List<Jsp.Content> nodes) {
        return ListUtils.flatMap(nodes, node -> {
            Jsp.Content n = node;
            if (n instanceof Jsp.Tag && ((Jsp.Tag) n).getBody() != null) {
                Jsp.Tag tag = (Jsp.Tag) n;
                n = tag.withBody(annotate(tag.getBody()));
            }
            TreeMap<Integer, List<String>> messages = messagesByAnchor.get(node.getId());
            if (messages == null) {
                return n;
            }
            if (n instanceof Jsp.Text) {
                return split((Jsp.Text) n, messages);
            }
            List<String> all = new ArrayList<>();
            messages.values().forEach(all::addAll);
            return SearchResult.found(n, String.join("; ", all));
        });
    }

    private static List<Jsp.Text> split(Jsp.Text text, TreeMap<Integer, List<String>> messages) {
        String s = text.getText();
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int offset : messages.keySet()) {
            if (offset > 0 && offset < s.length()) {
                starts.add(offset);
            }
        }
        List<Jsp.Text> segments = new ArrayList<>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : s.length();
            Jsp.Text segment = i == 0 ?
                    text.withText(s.substring(start, end)) :
                    new Jsp.Text(randomId(), Markers.EMPTY, s.substring(start, end));
            List<String> here = new ArrayList<>(messages.getOrDefault(start, List.of()));
            if (i == 0) {
                // Node-level messages (offset -1) belong at the start, too.
                here.addAll(0, messages.getOrDefault(-1, List.of()));
            }
            segments.add(here.isEmpty() ? segment : SearchResult.found(segment, String.join("; ", here)));
        }
        return segments;
    }

    // -----------------------------------------------------------------------------------------
    // Walking the tree
    // -----------------------------------------------------------------------------------------

    private void walk(List<Jsp.Content> nodes) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Text) {
                scanText((Jsp.Text) node);
            } else if (node instanceof Jsp.Directive) {
                visitDirective((Jsp.Directive) node);
            } else if (node instanceof Jsp.Scriptlet) {
                Jsp.Scriptlet scriptlet = (Jsp.Scriptlet) node;
                checkInHtmlComment(scriptlet, "a scriptlet <% %>");
                visitJavaCode(scriptlet, scriptlet.getCode(), "scriptlet");
            } else if (node instanceof Jsp.Declaration) {
                Jsp.Declaration declaration = (Jsp.Declaration) node;
                checkInHtmlComment(declaration, "a declaration <%! %>");
                visitJavaCode(declaration, declaration.getCode(), "declaration");
            } else if (node instanceof Jsp.ExpressionScriptlet || node instanceof Jsp.ExpressionLanguage) {
                if (node instanceof Jsp.ExpressionScriptlet) {
                    checkInHtmlComment(node, "an expression <%= %>");
                    useJava(((Jsp.ExpressionScriptlet) node).getCode(), node);
                } else {
                    useEl(((Jsp.ExpressionLanguage) node).getExpression(), node);
                }
                visitExpression(node, node instanceof Jsp.ExpressionLanguage ? "EL expression" : "expression");
                advance(print(node));
            } else if (node instanceof Jsp.Tag) {
                visitTag((Jsp.Tag) node);
            } else {
                advance(print(node));
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Variables defined in included files
    // -----------------------------------------------------------------------------------------

    /**
     * Records a definition. In an included file, it's one the page may come to depend on; in the
     * page itself, it shadows any included definition of the same name from here on.
     */
    private void define(JspVariables.Definition definition, Location location) {
        String name = definition.name();
        JspVariables.Kind kind = definition.kind();
        IncludedDefinition included = new IncludedDefinition(kind, location);
        if (kind == JspVariables.Kind.JAVA_METHOD) {
            put(includedJavaMethods, name, included);
            return;
        }
        if (kind.visibleToJava()) {
            put(includedJavaVariables, name, included);
        }
        if (kind.visibleToEl()) {
            put(includedScopedVariables, name, included);
        }
    }

    private void put(Map<String, IncludedDefinition> definitions, String name, IncludedDefinition definition) {
        if (includeAnchor != null) {
            definitions.put(name, definition);
        } else {
            definitions.remove(name);
        }
    }

    private void useJava(String code, Jsp node) {
        if (includeAnchor != null) {
            return;
        }
        for (JspVariables.Use use : JspVariables.javaUses(code)) {
            Map<String, IncludedDefinition> definitions = use.scoped() ? includedScopedVariables :
                    use.call() ? includedJavaMethods : includedJavaVariables;
            reportUse(use.name(), definitions.get(use.name()), node);
        }
    }

    private void useEl(String expression, Jsp node) {
        if (includeAnchor != null) {
            return;
        }
        for (String name : JspVariables.elUses(expression)) {
            reportUse(name, includedScopedVariables.get(name), node);
        }
    }

    private void useScoped(String name, Jsp node) {
        if (includeAnchor == null) {
            reportUse(name, includedScopedVariables.get(name), node);
        }
    }

    /**
     * Reports the first use of each variable the page takes from an included file.
     */
    private void reportUse(String name, @Nullable IncludedDefinition definition, Jsp node) {
        if (definition == null || !reportedVariables.add(name)) {
            return;
        }
        report(Rule.VARIABLE_FROM_INCLUDE, here(node), null, false,
                "'" + name + "' is a " + definition.kind().description + " defined in included file " +
                inFile(definition.location()) + ": the page depends on a variable it does not define itself, " +
                "so it can't be read or changed on its own; define the variable in the page, or pass it " +
                "explicitly (e.g. as a request attribute set before the include)");
    }

    /**
     * JSP code inside an HTML comment {@code <!-- -->} is not commented out: it still runs on the
     * server, and only its output ends up inside the comment sent to the browser. Usually a JSP
     * comment {@code <%-- --%>} was meant. Reported once per HTML comment, at its start. EL is
     * deliberately not included, since {@code <!-- ${debugInfo} -->} is often intentional.
     */
    private void checkInHtmlComment(Jsp node, String what) {
        if (state != State.COMMENT || commentReported) {
            return;
        }
        commentReported = true;
        report(Rule.JSP_IN_HTML_COMMENT, commentLocation, null, false,
                "HTML comment <!-- --> contains " + what + " at " + where(here(node)) + ", which is not " +
                "commented out: it still runs on the server and its output is sent to the browser. " +
                "Was a JSP comment <%-- --%> intended?");
    }

    private void visitDirective(Jsp.Directive directive) {
        checkInHtmlComment(directive, "a directive <%@ " + directive.getName() + " %>");
        Location location = here(directive);
        directive.getMarkers().findFirst(ParseWarning.class).ifPresent(warning ->
                add(Rule.UNRESOLVED_INCLUDE, location,
                        warning.getMessage() + "; tag balance is not checked for this page"));
        advance(print(directive));

        Jsp.IncludedFile includedFile = directive.getIncludedFile();
        if (includedFile != null) {
            if (inHtmlTag()) {
                report(Rule.TAG_SPLIT_ACROSS_FILES, location, null, false,
                        "HTML tag <" + (endTag ? "/" : "") + tagName + "> starting in " + inFile(tagLocation) +
                        " continues in included file " + includedFile.getSourcePath() + ": keep each tag " +
                        "within one file");
            }
            Location tagBeforeInclude = tagLocation;

            Path savedFile = file;
            int savedLine = line;
            UUID savedAnchor = includeAnchor;
            int savedJavaDepth = javaDepth;
            javaDepth = 0;
            file = includedFile.getSourcePath();
            line = 1;
            if (includeAnchor == null) {
                includeAnchor = directive.getId();
            }
            walk(includedFile.getNodes());
            Location tagInInclude = tagLocation;
            file = savedFile;
            line = savedLine;
            includeAnchor = savedAnchor;
            javaDepth = savedJavaDepth;

            if (inHtmlTag() && tagInInclude != tagBeforeInclude) {
                report(Rule.TAG_SPLIT_ACROSS_FILES, location, null, false,
                        "HTML tag <" + (endTag ? "/" : "") + tagName + "> starting in " + inFile(tagInInclude) +
                        " is not finished there but continues in " + file + " after the include: keep each " +
                        "tag within one file");
            }
        }
    }

    private void visitJavaCode(Jsp node, String code, String kind) {
        Location location = here(node);
        if (inHtmlTag()) {
            report(Rule.SCRIPTLET_IN_HTML_TAG, location, null, false,
                    "Java " + kind + " inside HTML tag <" + tagName + ">: move the logic outside the tag, " +
                    "e.g. compute the attribute value first and output it with an expression");
        }
        if (HTML_IN_JAVA.matcher(code).find()) {
            report(Rule.HTML_IN_SCRIPTLET, location, null, false,
                    "HTML markup written from Java string literals: it is invisible to readers and tools " +
                    "that read the template; write it as template text instead");
        }
        int lines = countCodeLines(code);
        if (lines > maxScriptletLines) {
            report(Rule.LARGE_SCRIPTLET, location, null, false,
                    "Java " + kind + " of " + lines + " lines (more than " + maxScriptletLines + "): " +
                    "move the logic out of the page, e.g. into a servlet, bean, or tag");
        }
        boolean declaration = node instanceof Jsp.Declaration;
        for (JspVariables.Definition definition : JspVariables.javaDeclarations(code, declaration,
                declaration ? 0 : javaDepth)) {
            int definitionLine = location.line() + (int) code.substring(0, definition.offset()).chars()
                    .filter(ch -> ch == '\n').count();
            define(definition, new Location(file, definitionLine, location.anchor()));
        }
        // After recording the page's own declarations, which shadow included ones.
        useJava(code, node);
        if (node instanceof Jsp.Scriptlet) {
            int[] braces = braces(code);
            javaDepth = Math.max(0, javaDepth - braces[0]) + braces[1];
            for (int i = 0; i < braces[0]; i++) {
                exitBlock(true);
            }
            for (int i = 0; i < braces[1]; i++) {
                stack.add(new Barrier("the Java block opened at " + where(location), location, true));
            }
        }
        advance(print(node));
    }

    /**
     * An expression scriptlet, EL expression, or body-less custom tag: fine as (part of) an
     * attribute value, but generating attributes themselves (in an attribute <em>name</em>
     * position) makes the tag's real attributes impossible to see.
     */
    private void visitExpression(Jsp node, String kind) {
        if (!inHtmlTag()) {
            return;
        }
        if (state == State.ATTR_VALUE || expectingValue || inUnquotedValue) {
            if (expectingValue) {
                expectingValue = false;
                inUnquotedValue = true;
            }
            return;
        }
        report(Rule.GENERATED_ATTRIBUTE, here(node), null, false,
                "The " + kind + " generates attributes of HTML tag <" + tagName + ">: write the attribute " +
                "out and compute only its value");
    }

    private void visitTag(Jsp.Tag tag) {
        checkInHtmlComment(tag, "a JSP tag <" + tag.getName() + ">");
        Location location = here(tag);
        for (Jsp.Attribute attribute : tag.getAttributes()) {
            String value = attribute.getValue().getValue();
            // Uses first: in <c:set var="x" value="${x + 1}"/>, the x read is the earlier one.
            JspVariables.elInText(value).forEach(el -> useEl(el, tag));
            JspVariables.expressionsInText(value).forEach(code -> useJava(code, tag));
            if (("jsp:getProperty".equals(tag.getName()) || "jsp:setProperty".equals(tag.getName())) &&
                "name".equals(attribute.getName())) {
                useScoped(value, tag);
            }
            JspVariables.Definition definition = JspVariables.tagDefinition(tag.getName(), attribute.getName(), value);
            if (definition != null) {
                define(definition, location);
            }
        }
        String start = print(tag.isSelfClosing() ? tag : tag.withBody(List.of()).withClosing(null));

        if (inHtmlTag()) {
            if (tag.isSelfClosing()) {
                visitExpression(tag, "custom tag <" + tag.getName() + "/>");
            } else {
                report(Rule.SCRIPTLET_IN_HTML_TAG, location, null, false,
                        "Custom tag <" + tag.getName() + "> inside HTML tag <" + tagName + ">: move the logic " +
                        "outside the tag, e.g. compute the attribute value into a variable first");
            }
            advance(start);
            if (tag.getBody() != null) {
                walk(tag.getBody());
            }
            if (tag.getClosing() != null) {
                advance(closingText(tag.getClosing()));
            }
            return;
        }

        advance(start);
        if (tag.isSelfClosing()) {
            return;
        }
        stack.add(new Barrier("<" + tag.getName() + "> at " + where(location), location, false));
        walk(tag.getBody());
        exitBlock(false);

        Jsp.Tag.Closing closing = tag.getClosing();
        if (closing == null) {
            report(Rule.MISSING_END_TAG, location, location.file(), false,
                    "<" + tag.getName() + "> has no end tag </" + tag.getName() + ">");
            return;
        }
        if (!closing.getBeforeTagDelimiterPrefix().isBlank()) {
            report(Rule.END_TAG_WITH_ATTRIBUTES, new Location(file, line, location.anchor()), null, false,
                    "End tag </" + tag.getName() + "> has attributes or other content");
        }
        advance(closingText(closing));
    }

    // -----------------------------------------------------------------------------------------
    // HTML tokenizing
    // -----------------------------------------------------------------------------------------

    private void scanText(Jsp.Text text) {
        String s = text.getText();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (state) {
                case DATA:
                    if (c == '<') {
                        i = startMarkup(text, s, i);
                    }
                    break;
                case COMMENT:
                    if (s.startsWith("-->", i)) {
                        state = State.DATA;
                        i += 2;
                    }
                    break;
                case BOGUS_COMMENT:
                    if (c == '>') {
                        state = State.DATA;
                    }
                    break;
                case RAW_TEXT:
                    if (c == '<' && s.startsWith("/", i + 1) &&
                        s.regionMatches(true, i + 2, rawTextElement, 0, rawTextElement.length()) &&
                        isTagNameEnd(s, i + 2 + rawTextElement.length())) {
                        beginTag(text, i, rawTextElement, true);
                        closesRawText = true;
                        i += 1 + rawTextElement.length();
                    }
                    break;
                case ATTR_VALUE:
                    if (c == quote) {
                        state = State.IN_TAG;
                        lastNonWhitespace = c;
                    }
                    break;
                case IN_TAG:
                    scanInTag(c);
                    break;
            }
            if (c == '\n') {
                line++;
            }
        }
    }

    /**
     * Handles a {@code <} in {@link State#DATA}.
     *
     * @return the index of the last character consumed.
     */
    private int startMarkup(Jsp.Text text, String s, int i) {
        if (s.startsWith("<!--", i)) {
            state = State.COMMENT;
            commentLocation = new Location(file, line, anchor(text.getId(), i));
            commentReported = false;
            return i + 3;
        }
        if (s.startsWith("<!", i) || s.startsWith("<?", i)) {
            state = State.BOGUS_COMMENT;
            return i + 1;
        }
        boolean end = s.startsWith("</", i);
        int nameStart = i + (end ? 2 : 1);
        if (nameStart >= s.length() || !Character.isLetter(s.charAt(nameStart))) {
            return i;
        }
        int nameEnd = nameStart;
        while (nameEnd < s.length() && isTagNameChar(s.charAt(nameEnd))) {
            nameEnd++;
        }
        String name = s.substring(nameStart, nameEnd);
        beginTag(text, i, name.contains(":") ? name : name.toLowerCase(Locale.ROOT), end);
        return nameEnd - 1;
    }

    private void beginTag(Jsp.Text text, int offset, String name, boolean end) {
        state = State.IN_TAG;
        tagName = name;
        endTag = end;
        endTagHasContent = false;
        closesRawText = false;
        expectingValue = false;
        inUnquotedValue = false;
        lastNonWhitespace = ' ';
        tagLocation = new Location(file, line, anchor(text.getId(), offset));
    }

    private void scanInTag(char c) {
        boolean whitespace = Character.isWhitespace(c);
        if (endTag) {
            if (c == '>') {
                finishTag(false);
            } else if (!whitespace) {
                endTagHasContent = true;
            }
            return;
        }
        if (c == '>') {
            finishTag(!expectingValue && !inUnquotedValue && lastNonWhitespace == '/');
            return;
        }
        if (expectingValue) {
            if (c == '"' || c == '\'') {
                state = State.ATTR_VALUE;
                quote = c;
                expectingValue = false;
            } else if (!whitespace) {
                expectingValue = false;
                inUnquotedValue = true;
            }
        } else if (inUnquotedValue) {
            if (whitespace) {
                inUnquotedValue = false;
            }
        } else if (c == '=') {
            expectingValue = true;
        }
        if (!whitespace) {
            lastNonWhitespace = c;
        }
    }

    private void finishTag(boolean selfClosingSyntax) {
        state = State.DATA;
        Location location = tagLocation;
        if (endTag) {
            if (endTagHasContent) {
                report(Rule.END_TAG_WITH_ATTRIBUTES, location, null, false,
                        "End tag </" + tagName + "> has attributes or other content, which browsers ignore");
            }
            if (!closesRawText) {
                closeElement(tagName, location);
            }
        } else if (RAW_TEXT.contains(tagName)) {
            state = State.RAW_TEXT;
            rawTextElement = tagName;
        } else if (!selfClosingSyntax && !VOID.contains(tagName)) {
            stack.add(new Element(tagName, location));
        }
    }

    // -----------------------------------------------------------------------------------------
    // Element stack
    // -----------------------------------------------------------------------------------------

    private void closeElement(String name, Location endTagLocation) {
        Barrier crossed = null;
        int index = -1;
        for (int i = stack.size() - 1; i >= 0; i--) {
            Object entry = stack.get(i);
            if (entry instanceof Barrier) {
                if (crossed == null) {
                    crossed = (Barrier) entry;
                }
            } else if (((Element) entry).name.equals(name)) {
                index = i;
                break;
            }
        }

        if (index < 0) {
            report(Rule.UNMATCHED_END_TAG, endTagLocation, null, true,
                    "End tag </" + name + "> has no matching start tag");
            return;
        }
        Element element = (Element) stack.get(index);
        Path startFile = element.location.file();
        Path endFile = endTagLocation.file();
        boolean betweenIncludedFiles = !startFile.equals(pageFile) && !endFile.equals(pageFile);
        if (!startFile.equals(endFile) && !(allowSplitBetweenIncludedFiles && betweenIncludedFiles)) {
            report(Rule.TAG_SPLIT_ACROSS_FILES, endTagLocation, element.location.file(), false,
                    "End tag </" + name + "> in " + inFile(endTagLocation) + " closes the <" + name +
                    "> opened in " + inFile(element.location) + ": keep an element's start and end tag " +
                    "in the same file so each file is readable on its own");
        }
        if (crossed != null) {
            report(Rule.ELEMENT_CROSSES_BLOCK, endTagLocation, element.location.file(), false,
                    "End tag </" + name + "> is inside " + crossed.description() + " but closes the <" + name +
                    "> opened outside it at " + where(element.location) +
                    ": the page's structure then depends on which branch runs");
            stack.remove(index);
            return;
        }
        // Everything opened after the element is implicitly closed by its end tag.
        while (stack.size() > index + 1) {
            Element unclosed = (Element) stack.remove(stack.size() - 1);
            if (!unclosed.reported && requiresEndTag(unclosed.name)) {
                report(Rule.MISSING_END_TAG, unclosed.location, endTagLocation.file(), false,
                        "<" + unclosed.name + "> has no end tag (it is implicitly closed by </" + name +
                        "> at " + where(endTagLocation) + ")");
            }
        }
        stack.remove(index);
    }

    /**
     * Ends the innermost block of the given kind (a JSP action body or a Java block). Elements
     * opened inside it and still open are reported, and then left open in the enclosing scope so
     * a later end tag still matches them.
     */
    private void exitBlock(boolean javaBlock) {
        int index = -1;
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i) instanceof Barrier && ((Barrier) stack.get(i)).javaBlock() == javaBlock) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return;
        }
        Barrier barrier = (Barrier) stack.get(index);
        List<Object> inside = new ArrayList<>(stack.subList(index + 1, stack.size()));
        stack.subList(index, stack.size()).clear();
        for (Object entry : inside) {
            if (entry instanceof Element) {
                Element element = (Element) entry;
                if (!element.reported && requiresEndTag(element.name)) {
                    report(Rule.ELEMENT_CROSSES_BLOCK, element.location, barrier.location().file(), false,
                            "<" + element.name + "> is opened inside " + barrier.description() +
                            " but not closed inside it: the page's structure then depends on which branch runs");
                }
                element.reported = true;
                stack.add(element);
            }
            // A block of the other kind left open inside this one ends with it.
        }
    }

    // -----------------------------------------------------------------------------------------
    // Reporting
    // -----------------------------------------------------------------------------------------

    /**
     * @param relatedFile the file of the other end of the problem (e.g. where an implicitly closed
     *                    element's end tag is), if any. A problem entirely inside one included file
     *                    is that file's own business: it's reported when the file itself is analyzed.
     * @param pageLevel   whether the problem only exists in the context of the whole page (an
     *                    element never closed by the end of it, or an end tag with no start tag
     *                    anywhere), so fragments, whose includers may close or open it, don't report it.
     */
    private void report(Rule rule, @Nullable Location location, @Nullable Path relatedFile, boolean pageLevel,
                        String message) {
        if (location == null || (rule.balance && !balanceChecked) || (pageLevel && fragment)) {
            return;
        }
        if (!location.file().equals(pageFile) &&
            (!rule.balance || (!pageLevel && location.file().equals(relatedFile)))) {
            return;
        }
        add(rule, location, message);
    }

    private void add(Rule rule, Location location, String message) {
        String text = location.file().equals(pageFile) ? message : message + " (in " + location.file() + ")";
        problems.add(new Problem(rule, location.file(), location.line(), message));
        messagesByAnchor.computeIfAbsent(location.anchor().nodeId(), id -> new TreeMap<>())
                .computeIfAbsent(location.anchor().offset(), o -> new ArrayList<>())
                .add(rule + ": " + text);
    }

    private Location here(Jsp node) {
        return new Location(file, line, anchor(node.getId(), -1));
    }

    /**
     * Problems inside an included file are anchored to the page's include directive, since the
     * included file itself must not be changed through the page.
     */
    private Anchor anchor(UUID nodeId, int offset) {
        return includeAnchor == null ? new Anchor(nodeId, offset) : new Anchor(includeAnchor, -1);
    }

    private static String inFile(@Nullable Location location) {
        return location == null ? "?" : location.file() + " line " + location.line();
    }

    private String where(Location location) {
        return location.file().equals(pageFile) ? "line " + location.line() :
                location.file() + " line " + location.line();
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private boolean inHtmlTag() {
        return state == State.IN_TAG || state == State.ATTR_VALUE;
    }

    private static boolean requiresEndTag(String name) {
        return !OPTIONAL_END_TAG.contains(name);
    }

    private void advance(String printed) {
        for (int i = 0; i < printed.length(); i++) {
            if (printed.charAt(i) == '\n') {
                line++;
            }
        }
    }

    private static String print(Jsp node) {
        PrintOutputCapture<Integer> out = new PrintOutputCapture<>(0);
        new JspPrinter<Integer>().visit(node, out);
        return out.getOut();
    }

    private static String closingText(Jsp.Tag.Closing closing) {
        return "</" + closing.getPrefix() + closing.getName() + closing.getBeforeTagDelimiterPrefix() + ">";
    }

    private static boolean isTagNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == ':' || c == '.';
    }

    private static boolean isTagNameEnd(String s, int i) {
        return i >= s.length() || Character.isWhitespace(s.charAt(i)) || s.charAt(i) == '>' || s.charAt(i) == '/';
    }

    private static int countCodeLines(String code) {
        int lines = 0;
        for (String l : code.split("\n", -1)) {
            if (!l.isBlank()) {
                lines++;
            }
        }
        return lines;
    }

    /**
     * @return {@code {closes, opens}}: how many enclosing Java blocks the code closes (unmatched
     * {@code }}), then how many it leaves open (unmatched <code>{</code>). E.g. {@code } else {} is
     * {@code {1, 1}}. Braces in string/char literals and comments are ignored.
     */
    static int[] braces(String code) {
        int depth = 0;
        int closes = 0;
        int n = code.length();
        for (int i = 0; i < n; i++) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                for (i++; i < n && code.charAt(i) != c; i++) {
                    if (code.charAt(i) == '\\') {
                        i++;
                    }
                }
            } else if (c == '/' && i + 1 < n && code.charAt(i + 1) == '/') {
                while (i < n && code.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && code.charAt(i + 1) == '*') {
                int close = code.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (depth > 0) {
                    depth--;
                } else {
                    closes++;
                }
            }
        }
        return new int[]{closes, depth};
    }
}
