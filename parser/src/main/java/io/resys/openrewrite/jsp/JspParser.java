package io.resys.openrewrite.jsp;

import org.intellij.lang.annotations.Language;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.internal.EncodingDetectingInputStream;
import io.resys.openrewrite.jsp.tree.Jsp;
import org.openrewrite.marker.Markers;
import org.openrewrite.tree.ParseError;
import org.openrewrite.tree.ParsingEventListener;
import org.openrewrite.tree.ParsingExecutionContextView;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static java.util.Collections.unmodifiableList;
import static org.openrewrite.Tree.randomId;

/**
 * A hand-written recursive-descent parser for the "standard syntax" defined by the
 * <a href="https://jakarta.ee/specifications/pages/4.0/jakarta-server-pages-spec-4.0">Jakarta Server Pages 4.0</a>
 * specification (JSP source files using {@code <% %>}-style constructs, as opposed to the
 * alternative all-XML "JSP document" syntax, which is not yet supported).
 * <p>
 * Scope, by design: only JSP-specific constructs are parsed into structured nodes -
 * directives, scriptlets, declarations, expression scriptlets, JSP comments, EL expressions,
 * and standard/custom actions (elements with a namespace-prefixed name, e.g. {@code jsp:include}
 * or {@code c:if}). Everything else, including all plain HTML markup, is preserved verbatim as
 * {@link Jsp.Text}. See {@link Jsp} for the full rationale.
 * <p>
 * Known limitations (documented rather than silently mishandled):
 * <ul>
 *     <li>Scriptlet/declaration/expression bodies are terminated by the first unescaped {@code %>};
 *     a {@code %>} inside a Java string or character literal in that code will end the tag early.</li>
 *     <li>EL expressions embedded inside a quoted attribute value are kept as raw text, not
 *     decomposed into a sub-tree.</li>
 *     <li>The all-XML "JSP document" syntax ({@code .jspx}) and tag files ({@code .tag}/{@code .tagx})
 *     are not handled by this parser.</li>
 * </ul>
 */
public class JspParser implements Parser {

    @Override
    public Stream<SourceFile> parse(@Language("JSP") String... sources) {
        return parse(new InMemoryExecutionContext(), sources);
    }

    @Override
    public Stream<SourceFile> parseInputs(Iterable<Input> sourceFiles, @Nullable Path relativeTo, ExecutionContext ctx) {
        ParsingEventListener parsingListener = ParsingExecutionContextView.view(ctx).getParsingListener();
        return acceptedInputs(sourceFiles).map(input -> {
            parsingListener.startedParsing(input);
            Path path = input.getRelativePath(relativeTo);
            try (EncodingDetectingInputStream is = input.getSource(ctx)) {
                Jsp.Document document = parseFromInput(path, is)
                        .withFileAttributes(input.getFileAttributes());
                parsingListener.parsed(input, document);
                return requirePrintEqualsInput(document, input, relativeTo, ctx);
            } catch (Throwable t) {
                ctx.getOnError().accept(t);
                return ParseError.build(this, input, relativeTo, ctx, t);
            }
        });
    }

    private Jsp.Document parseFromInput(Path sourcePath, EncodingDetectingInputStream source) {
        String text = source.readFully();
        Scanner scanner = new Scanner(text);
        List<Jsp.Content> nodes = parseNodes(scanner, null);
        return new Jsp.Document(
                randomId(),
                Markers.EMPTY,
                sourcePath,
                unmodifiableList(nodes),
                source.getCharset().name(),
                source.isCharsetBomMarked(),
                FileAttributes.fromPath(sourcePath),
                null
        );
    }

    @Override
    public boolean accept(Path path) {
        String s = path.toString();
        return s.endsWith(".jsp") || s.endsWith(".jspf");
    }

    @Override
    public Path sourcePathFromSourceText(Path prefix, String sourceCode) {
        return prefix.resolve("file.jsp");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder extends Parser.Builder {
        public Builder() {
            super(Jsp.Document.class);
        }

        @Override
        public JspParser build() {
            return new JspParser();
        }

        @Override
        public String getDslName() {
            return "jsp";
        }
    }

    // -----------------------------------------------------------------------------------------
    // Recursive-descent scanning
    // -----------------------------------------------------------------------------------------

    /**
     * Parses a run of top-level content, stopping either at end of input (when {@code stopTagName}
     * is {@code null}, i.e. we are at the document root) or just before a matching
     * {@code </stopTagName ...>} closing tag (when parsing a {@link Jsp.Tag}'s body), which is left
     * unconsumed for the caller to parse itself via {@link #parseClosing}.
     */
    private static List<Jsp.Content> parseNodes(Scanner sc, @Nullable String stopTagName) {
        List<Jsp.Content> nodes = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        while (true) {
            if (stopTagName != null && matchesClosingTag(sc, stopTagName)) {
                break;
            }
            if (sc.isEof()) {
                if (stopTagName != null) {
                    throw new JspParsingException("Unterminated tag <" + stopTagName +
                                                   ">: missing closing </" + stopTagName + ">");
                }
                break;
            }

            if (sc.startsWith("<\\%")) {
                text.append("<\\%");
                sc.advance(3);
            } else if (sc.startsWith("<%--")) {
                flushText(nodes, text);
                nodes.add(parseComment(sc));
            } else if (sc.startsWith("<%@")) {
                flushText(nodes, text);
                nodes.add(parseDirective(sc));
            } else if (sc.startsWith("<%!")) {
                flushText(nodes, text);
                nodes.add(parseDeclaration(sc));
            } else if (sc.startsWith("<%=")) {
                flushText(nodes, text);
                nodes.add(parseExpressionScriptlet(sc));
            } else if (sc.startsWith("<%")) {
                flushText(nodes, text);
                nodes.add(parseScriptlet(sc));
            } else if (sc.startsWith("\\${")) {
                text.append("\\${");
                sc.advance(3);
            } else if (sc.startsWith("\\#{")) {
                text.append("\\#{");
                sc.advance(3);
            } else if ((sc.peek() == '$' || sc.peek() == '#') && sc.peek(1) == '{') {
                flushText(nodes, text);
                nodes.add(parseExpressionLanguage(sc));
            } else if (sc.peek() == '<' && looksLikeTagStart(sc)) {
                flushText(nodes, text);
                nodes.add(parseTag(sc));
            } else {
                text.append(sc.peek());
                sc.advance(1);
            }
        }

        flushText(nodes, text);
        return nodes;
    }

    private static void flushText(List<Jsp.Content> nodes, StringBuilder text) {
        if (text.length() > 0) {
            nodes.add(new Jsp.Text(randomId(), Markers.EMPTY, text.toString()));
            text.setLength(0);
        }
    }

    private static Jsp.Comment parseComment(Scanner sc) {
        sc.advance(4); // "<%--"
        int close = sc.indexOf("--%>");
        if (close < 0) {
            throw new JspParsingException("Unterminated JSP comment: missing --%>");
        }
        String body = sc.substringToAbsolute(close);
        sc.advance(4); // "--%>"
        return new Jsp.Comment(randomId(), "", Markers.EMPTY, body);
    }

    private static Jsp.Directive parseDirective(Scanner sc) {
        sc.advance(3); // "<%@"
        String beforeName = sc.scanWhitespace();
        String name = sc.scanName();
        List<Jsp.Attribute> attributes = parseAttributes(sc);
        String beforeEnd = sc.scanWhitespace();
        if (!sc.startsWith("%>")) {
            throw new JspParsingException("Malformed directive <%@ " + name + " ...>: expected %>");
        }
        sc.advance(2);
        return new Jsp.Directive(randomId(), "", Markers.EMPTY, beforeName, name, attributes, beforeEnd);
    }

    private static Jsp.Declaration parseDeclaration(Scanner sc) {
        sc.advance(3); // "<%!"
        String code = scanCode(sc, "declaration");
        return new Jsp.Declaration(randomId(), "", Markers.EMPTY, code);
    }

    private static Jsp.Scriptlet parseScriptlet(Scanner sc) {
        sc.advance(2); // "<%"
        String code = scanCode(sc, "scriptlet");
        return new Jsp.Scriptlet(randomId(), "", Markers.EMPTY, code);
    }

    private static Jsp.ExpressionScriptlet parseExpressionScriptlet(Scanner sc) {
        sc.advance(3); // "<%="
        String code = scanCode(sc, "expression");
        return new Jsp.ExpressionScriptlet(randomId(), "", Markers.EMPTY, code);
    }

    /**
     * Scans Java code up to (and consuming) the closing {@code %>}. A literal {@code %\>} escape
     * sequence embedded in the code (used to emit a literal {@code %>} without ending the tag) is
     * never mistaken for the terminator: the backslash sitting between {@code %} and {@code >}
     * means those two characters never appear consecutively there, so a plain search for the
     * (unescaped) two-character token {@code "%>"} already skips right over it.
     */
    private static String scanCode(Scanner sc, String constructName) {
        int close = sc.indexOf("%>");
        if (close < 0) {
            throw new JspParsingException("Unterminated " + constructName + ": missing %>");
        }
        String code = sc.substringToAbsolute(close);
        sc.advance(2);
        return code;
    }

    /**
     * Scans an EL expression body, tracking brace depth and (E)L string literals so that nested
     * {@code {}} (e.g. map/list literals) and string-literal-embedded {@code }}/{@code {} do not
     * terminate the expression early.
     */
    private static Jsp.ExpressionLanguage parseExpressionLanguage(Scanner sc) {
        Jsp.ExpressionLanguage.Type type = sc.peek() == '#' ?
                Jsp.ExpressionLanguage.Type.DEFERRED : Jsp.ExpressionLanguage.Type.IMMEDIATE;
        sc.advance(2); // marker + '{'

        StringBuilder sb = new StringBuilder();
        int depth = 1;
        Character inString = null;
        while (true) {
            if (sc.isEof()) {
                throw new JspParsingException("Unterminated EL expression: missing '}'");
            }
            char c = sc.peek();
            if (inString != null) {
                sb.append(c);
                sc.advance(1);
                if (c == '\\' && !sc.isEof()) {
                    sb.append(sc.peek());
                    sc.advance(1);
                } else if (c == inString) {
                    inString = null;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                inString = c;
                sb.append(c);
                sc.advance(1);
            } else if (c == '{') {
                depth++;
                sb.append(c);
                sc.advance(1);
            } else if (c == '}') {
                depth--;
                sc.advance(1);
                if (depth == 0) {
                    break;
                }
                sb.append(c);
            } else {
                sb.append(c);
                sc.advance(1);
            }
        }
        return new Jsp.ExpressionLanguage(randomId(), "", Markers.EMPTY, type, sb.toString());
    }

    private static Jsp.Tag parseTag(Scanner sc) {
        sc.advance(1); // '<'
        String name = sc.scanName();
        List<Jsp.Attribute> attributes = parseAttributes(sc);
        String beforeDelim = sc.scanWhitespace();

        boolean selfClosing;
        if (sc.startsWith("/>")) {
            selfClosing = true;
            sc.advance(2);
        } else if (sc.startsWith(">")) {
            selfClosing = false;
            sc.advance(1);
        } else {
            throw new JspParsingException("Malformed tag <" + name + " ...>: expected '>' or '/>'");
        }

        if (selfClosing) {
            return new Jsp.Tag(randomId(), "", Markers.EMPTY, name, attributes, true, beforeDelim, null, null);
        }

        List<Jsp.Content> body = parseNodes(sc, name);
        Jsp.Tag.Closing closing = parseClosing(sc);
        return new Jsp.Tag(randomId(), "", Markers.EMPTY, name, attributes, false, beforeDelim, body, closing);
    }

    private static Jsp.Tag.Closing parseClosing(Scanner sc) {
        sc.advance(2); // "</"
        String beforeName = sc.scanWhitespace();
        String name = sc.scanName();
        String beforeDelim = sc.scanWhitespace();
        if (!sc.startsWith(">")) {
            throw new JspParsingException("Malformed closing tag </" + name + " ...>: expected '>'");
        }
        sc.advance(1);
        return new Jsp.Tag.Closing(randomId(), beforeName, Markers.EMPTY, name, beforeDelim);
    }

    private static List<Jsp.Attribute> parseAttributes(Scanner sc) {
        List<Jsp.Attribute> attributes = new ArrayList<>();
        while (true) {
            int save = sc.pos;
            String ws = sc.scanWhitespace();
            if (sc.isEof() || sc.startsWith("/>") || sc.startsWith(">") || sc.startsWith("%>") || !isNameStart(sc.peek())) {
                sc.pos = save;
                break;
            }

            String name = sc.scanName();
            String beforeEquals = sc.scanWhitespace();
            if (!sc.startsWith("=")) {
                throw new JspParsingException("Attribute '" + name + "' must have a quoted value");
            }
            sc.advance(1);
            String valuePrefix = sc.scanWhitespace();
            if (sc.isEof() || (sc.peek() != '"' && sc.peek() != '\'')) {
                throw new JspParsingException("Attribute '" + name + "' value must be quoted");
            }
            char quote = sc.peek();
            sc.advance(1);
            String value = sc.scanQuotedValue(quote);
            if (value == null) {
                throw new JspParsingException("Unterminated attribute value for '" + name + "'");
            }

            Jsp.Attribute.Value attrValue = new Jsp.Attribute.Value(randomId(), valuePrefix, Markers.EMPTY, quote, value);
            attributes.add(new Jsp.Attribute(randomId(), ws, Markers.EMPTY, name, beforeEquals, attrValue));
        }
        return attributes;
    }

    /**
     * @return {@code true} if a {@code </stopTagName} (followed by whitespace or {@code >}) appears
     * at the scanner's current position, without consuming any input.
     */
    private static boolean matchesClosingTag(Scanner sc, String stopTagName) {
        if (!sc.startsWith("</")) {
            return false;
        }
        int p = sc.pos + 2;
        while (p < sc.s.length() && Character.isWhitespace(sc.s.charAt(p))) {
            p++;
        }
        if (!sc.s.startsWith(stopTagName, p)) {
            return false;
        }
        int after = p + stopTagName.length();
        if (after >= sc.s.length()) {
            return false;
        }
        char c = sc.s.charAt(after);
        return Character.isWhitespace(c) || c == '>';
    }

    /**
     * @return {@code true} if the scanner is positioned at a {@code '<'} that begins a namespace-prefixed
     * element name (e.g. {@code <c:if}), which per the JSP spec always denotes a standard or custom
     * action, as opposed to plain (unprefixed) HTML/XML markup, which is left as {@link Jsp.Text}.
     */
    private static boolean looksLikeTagStart(Scanner sc) {
        char next = sc.peek(1);
        if (next == '/' || next == '!' || next == '?' || next == '%' || !isNameStart(next)) {
            return false;
        }
        int p = sc.pos + 1;
        boolean sawColon = false;
        while (p < sc.s.length()) {
            char c = sc.s.charAt(p);
            if (c == ':') {
                sawColon = true;
                p++;
            } else if (isNameChar(c)) {
                p++;
            } else {
                break;
            }
        }
        if (!sawColon || p >= sc.s.length()) {
            return false;
        }
        char after = sc.s.charAt(p);
        return Character.isWhitespace(after) || after == '>' || after == '/';
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':';
    }

    /**
     * A simple mutable cursor over the source text.
     */
    private static final class Scanner {
        final String s;
        int pos;

        Scanner(String s) {
            this.s = s;
            this.pos = 0;
        }

        boolean isEof() {
            return pos >= s.length();
        }

        char peek() {
            return peek(0);
        }

        char peek(int ahead) {
            int i = pos + ahead;
            return i < s.length() ? s.charAt(i) : '\0';
        }

        boolean startsWith(String token) {
            return s.startsWith(token, pos);
        }

        void advance(int n) {
            pos += n;
        }

        /**
         * @return the absolute index into {@link #s} of the first occurrence of {@code token} at
         * or after the current position, or a negative value if not found. Pass the result to
         * {@link #substringToAbsolute} to consume up to it.
         */
        int indexOf(String token) {
            return s.indexOf(token, pos);
        }

        /**
         * @return the text between the current position and the given absolute index, without
         * advancing the cursor.
         */
        String substringToAbsolute(int absoluteEnd) {
            String result = s.substring(pos, absoluteEnd);
            pos = absoluteEnd;
            return result;
        }

        String scanWhitespace() {
            int start = pos;
            while (!isEof() && Character.isWhitespace(peek())) {
                pos++;
            }
            return s.substring(start, pos);
        }

        String scanName() {
            int start = pos;
            while (!isEof() && isNameChar(peek())) {
                pos++;
            }
            return s.substring(start, pos);
        }

        /**
         * Scans (and consumes, including the closing delimiter) the raw text of a quoted attribute
         * value up to the first un-escaped {@code quote} character. A backslash immediately before
         * {@code quote} (e.g. {@code \"} inside a double-quoted value) escapes it per the JSP
         * attribute quoting rules, so it does not terminate the value; the raw text - backslashes
         * included - is returned unmodified.
         *
         * @return the raw value text, or {@code null} if the closing quote is never found.
         */
        @Nullable
        String scanQuotedValue(char quote) {
            int start = pos;
            while (true) {
                if (isEof()) {
                    return null;
                }
                char c = peek();
                if (c == '\\' && pos + 1 < s.length()) {
                    pos += 2;
                } else if (c == quote) {
                    String value = s.substring(start, pos);
                    pos++;
                    return value;
                } else {
                    pos++;
                }
            }
        }
    }
}
