package io.resys.orw.jsp;

import org.intellij.lang.annotations.Language;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.internal.EncodingDetectingInputStream;
import io.resys.orw.jsp.internal.TagLibraryResolver;
import io.resys.orw.jsp.tree.Jsp;
import io.resys.orw.jsp.tree.TagLibrary;
import org.openrewrite.marker.Markers;
import org.openrewrite.tree.ParseError;
import org.openrewrite.tree.ParsingEventListener;
import org.openrewrite.tree.ParsingExecutionContextView;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Static includes ({@code <%@ include file="..." %>}) are resolved and the included file's parsed
 * content is embedded, read-only, as {@link Jsp.Directive#getIncludedFile()} (see there). A relative
 * {@code file} is resolved against the directory of the file containing the directive; a
 * context-relative one (leading {@code /}) against the web application root, taken to be the
 * nearest ancestor directory containing {@code WEB-INF} (or, failing that, the nearest ancestor
 * under which the target exists). The target is looked up first among the inputs of the same
 * {@link #parseInputs} call, then on disk. An include that cannot be found, read, or parsed is left
 * with a {@code null} included file and a {@link ParseWarning} marker on the directive explaining
 * why, rather than failing the including page. A recursive include is also left {@code null}, but
 * without a warning: the file it names is already part of the tree.
 * <p>
 * The tag library descriptor (TLD) of each {@code <%@ taglib uri="..." %>} is resolved, if it can
 * be, and attached to the directive as a {@link TagLibrary} marker (see
 * {@link TagLibraryResolver} for the lookup order: explicit {@link Builder#taglib} mappings, the
 * {@link Builder#tldSearchPath}, then the web application's {@code web.xml}, {@code WEB-INF} and
 * {@code WEB-INF/lib} jars). An unresolved taglib simply has no marker; it is not a warning, since
 * libraries that live in dependency jars commonly can't be resolved from the source tree.
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

    private final Map<String, Path> taglibs;
    private final List<Path> tldSearchPath;

    public JspParser() {
        this(Map.of(), List.of());
    }

    /**
     * @param taglibs       explicit taglib uri to TLD file (or to a jar or directory containing the
     *                      TLD declaring that uri) mappings.
     * @param tldSearchPath directories and jars searched for TLDs by their {@code <uri>}.
     * @see Builder#taglib(String, Path)
     * @see Builder#tldSearchPath(Path...)
     */
    public JspParser(Map<String, Path> taglibs, List<Path> tldSearchPath) {
        this.taglibs = Map.copyOf(taglibs);
        this.tldSearchPath = List.copyOf(tldSearchPath);
    }

    @Override
    public Stream<SourceFile> parse(@Language("JSP") String... sources) {
        return parse(new InMemoryExecutionContext(), sources);
    }

    @Override
    public Stream<SourceFile> parseInputs(Iterable<Input> sourceFiles, @Nullable Path relativeTo, ExecutionContext ctx) {
        ParsingEventListener parsingListener = ParsingExecutionContextView.view(ctx).getParsingListener();
        Includes includes = new Includes(sourceFiles, relativeTo, ctx, taglibs, tldSearchPath);
        return acceptedInputs(sourceFiles).map(input -> {
            parsingListener.startedParsing(input);
            Path path = input.getRelativePath(relativeTo);
            try (EncodingDetectingInputStream is = input.getSource(ctx)) {
                Jsp.Document document = parseFromInput(path, includes.absolute(input.getPath()), includes, is)
                        .withFileAttributes(input.getFileAttributes());
                parsingListener.parsed(input, document);
                return requirePrintEqualsInput(document, input, relativeTo, ctx);
            } catch (Throwable t) {
                ctx.getOnError().accept(t);
                return ParseError.build(this, input, relativeTo, ctx, t);
            }
        });
    }

    private Jsp.Document parseFromInput(Path sourcePath, Path absolutePath, Includes includes,
                                        EncodingDetectingInputStream source) {
        String text = source.readFully();
        Scanner scanner = new Scanner(text, new FileContext(includes, absolutePath, Set.of(absolutePath)));
        List<Jsp.Content> nodes = parseNodes(scanner, List.of());
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
        private final Map<String, Path> taglibs = new LinkedHashMap<>();
        private final List<Path> tldSearchPath = new ArrayList<>();

        public Builder() {
            super(Jsp.Document.class);
        }

        /**
         * Maps a taglib {@code uri} to the location of its TLD: a {@code .tld} file, or a jar or
         * directory containing a TLD that declares this {@code <uri>}. Relative paths are resolved
         * against the parse's {@code relativeTo} (or the working directory). Takes precedence over
         * every other way of resolving the uri.
         */
        public Builder taglib(String uri, Path location) {
            taglibs.put(uri, location);
            return this;
        }

        /**
         * Adds directories (searched recursively for {@code .tld} files and jars) and jars (searched in
         * {@code META-INF}) whose TLDs are matched to taglib uris by their {@code <uri>} element,
         * e.g. the JSTL jar of a Maven project, which isn't in the source tree's {@code WEB-INF/lib}.
         */
        public Builder tldSearchPath(Path... locations) {
            tldSearchPath.addAll(Arrays.asList(locations));
            return this;
        }

        @Override
        public JspParser build() {
            return new JspParser(taglibs, tldSearchPath);
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
     * Parses a run of content, stopping at end of input or just before a {@code </name ...>}
     * closing tag matching any of the {@code openTags} (the names of the {@link Jsp.Tag}s whose
     * bodies are being parsed, innermost last; empty at the document root), which is left
     * unconsumed for the caller to parse itself via {@link #parseClosing}.
     * <p>
     * Stopping at an <em>enclosing</em> tag's closing tag (or at end of input) rather than only at
     * the innermost one is how a missing end tag is recovered from: the innermost tag is then left
     * unclosed (see {@link #parseTag}) instead of swallowing the rest of the page or failing it.
     */
    private static List<Jsp.Content> parseNodes(Scanner sc, List<String> openTags) {
        List<Jsp.Content> nodes = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        while (true) {
            if (matchesAnyClosingTag(sc, openTags) || sc.isEof()) {
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
                nodes.add(parseTag(sc, openTags));
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
        Markers markers = Markers.EMPTY;
        Jsp.IncludedFile includedFile = null;
        if ("taglib".equals(name)) {
            for (Jsp.Attribute attribute : attributes) {
                if ("uri".equals(attribute.getName())) {
                    TagLibrary library = sc.file.includes().tagLibraries
                            .resolve(attribute.getValue().getValue(), sc.file.absolutePath());
                    if (library != null) {
                        markers = markers.add(library);
                    }
                }
            }
        }
        if ("include".equals(name)) {
            String file = "";
            for (Jsp.Attribute attribute : attributes) {
                if ("file".equals(attribute.getName())) {
                    file = attribute.getValue().getValue();
                }
            }
            try {
                includedFile = sc.file.includes().parse(sc.file, file);
            } catch (UnresolvedIncludeException e) {
                markers = markers.add(new ParseWarning(randomId(), e.getMessage()));
            }
        }
        return new Jsp.Directive(randomId(), "", markers, beforeName, name, attributes, beforeEnd, includedFile);
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

    private static Jsp.Tag parseTag(Scanner sc, List<String> openTags) {
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

        List<String> bodyOpenTags = new ArrayList<>(openTags);
        bodyOpenTags.add(name);
        List<Jsp.Content> body = parseNodes(sc, bodyOpenTags);
        // Missing end tag: the body ran to end of input or to an enclosing tag's end tag.
        Jsp.Tag.Closing closing = matchesClosingTag(sc, name) ? parseClosing(sc) : null;
        return new Jsp.Tag(randomId(), "", Markers.EMPTY, name, attributes, false, beforeDelim, body, closing);
    }

    /**
     * Parses a closing tag. Anything between the name and the {@code >} is kept verbatim as the
     * closing's {@link Jsp.Tag.Closing#getBeforeTagDelimiterPrefix()}: normally just whitespace,
     * but invalid trailing content (e.g. attributes on an end tag) is tolerated rather than failing
     * the page.
     */
    private static Jsp.Tag.Closing parseClosing(Scanner sc) {
        sc.advance(2); // "</"
        String beforeName = sc.scanWhitespace();
        String name = sc.scanName();
        int start = sc.pos;
        Character quote = null;
        while (!sc.isEof() && (quote != null || sc.peek() != '>')) {
            char c = sc.peek();
            if (quote != null) {
                if (c == quote) {
                    quote = null;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            }
            sc.advance(1);
        }
        if (sc.isEof()) {
            throw new JspParsingException("Malformed closing tag </" + name + " ...>: expected '>'");
        }
        String beforeDelim = sc.s.substring(start, sc.pos);
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

    private static boolean matchesAnyClosingTag(Scanner sc, List<String> openTags) {
        for (String openTag : openTags) {
            if (matchesClosingTag(sc, openTag)) {
                return true;
            }
        }
        return false;
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
     * The file being scanned, plus what's needed to resolve and parse its static includes.
     *
     * @param absolutePath the file's normalized absolute path.
     * @param chain        the absolute paths of every file on the current include chain, including
     *                     this one, used to refuse recursive includes.
     */
    private record FileContext(Includes includes, Path absolutePath, Set<Path> chain) {
    }

    /**
     * Resolves and parses the targets of {@code <%@ include file="..." %>} directives. All paths are
     * handled in normalized absolute form; inputs of the current parse batch take precedence over
     * files on disk.
     */
    private static final class Includes {
        private final Map<Path, Input> inputsByPath = new HashMap<>();
        private final Path base;
        private final ExecutionContext ctx;
        final TagLibraryResolver tagLibraries;

        Includes(Iterable<Input> inputs, @Nullable Path relativeTo, ExecutionContext ctx,
                 Map<String, Path> taglibs, List<Path> tldSearchPath) {
            this.base = (relativeTo == null ? Paths.get("") : relativeTo).toAbsolutePath().normalize();
            this.ctx = ctx;
            this.tagLibraries = new TagLibraryResolver(taglibs, tldSearchPath, base);
            for (Input input : inputs) {
                inputsByPath.putIfAbsent(absolute(input.getPath()), input);
            }
        }

        Path absolute(Path path) {
            return base.resolve(path).normalize();
        }

        /**
         * @return the parsed included file, or {@code null} if it is already on the include chain
         * (a recursive include, whose content is therefore already part of the tree).
         * @throws UnresolvedIncludeException if the included file can't be found, read, or parsed.
         */
        Jsp.@Nullable IncludedFile parse(FileContext including, String file) throws UnresolvedIncludeException {
            Path target = resolve(including.absolutePath(), file);
            if (target == null) {
                throw new UnresolvedIncludeException("Included file '" + file + "' not found");
            }
            if (including.chain().contains(target)) {
                return null;
            }
            String text = read(target);
            if (text == null) {
                throw new UnresolvedIncludeException("Included file '" + file + "' could not be read");
            }
            Set<Path> chain = new HashSet<>(including.chain());
            chain.add(target);
            List<Jsp.Content> nodes;
            try {
                nodes = parseNodes(new Scanner(text, new FileContext(this, target, chain)), List.of());
            } catch (JspParsingException e) {
                throw new UnresolvedIncludeException("Included file '" + file + "' could not be parsed: " +
                                                      e.getMessage());
            }
            Path sourcePath = target.startsWith(base) ? base.relativize(target) : target;
            return new Jsp.IncludedFile(randomId(), Markers.EMPTY, sourcePath, unmodifiableList(nodes));
        }

        private @Nullable Path resolve(Path includingFile, String file) {
            Path dir = includingFile.getParent();
            if (dir == null || file.isEmpty()) {
                return null;
            }
            if (!file.startsWith("/")) {
                Path target = dir.resolve(file).normalize();
                return exists(target) ? target : null;
            }

            String contextRelative = file.substring(1);
            for (Path d = dir; d != null; d = d.getParent()) {
                if (isWebRoot(d)) {
                    Path target = d.resolve(contextRelative).normalize();
                    return exists(target) ? target : null;
                }
            }
            for (Path d = dir; d != null; d = d.getParent()) {
                Path target = d.resolve(contextRelative).normalize();
                if (exists(target)) {
                    return target;
                }
            }
            return null;
        }

        private boolean isWebRoot(Path dir) {
            Path webInf = dir.resolve("WEB-INF");
            if (Files.isDirectory(webInf)) {
                return true;
            }
            for (Path path : inputsByPath.keySet()) {
                if (path.startsWith(webInf)) {
                    return true;
                }
            }
            return false;
        }

        private boolean exists(Path path) {
            return inputsByPath.containsKey(path) || Files.isRegularFile(path);
        }

        private @Nullable String read(Path path) {
            Input input = inputsByPath.get(path);
            try (EncodingDetectingInputStream is = input != null ?
                    input.getSource(ctx) : new EncodingDetectingInputStream(Files.newInputStream(path))) {
                return is.readFully();
            } catch (IOException | UncheckedIOException e) {
                return null;
            }
        }
    }

    /**
     * An include whose target can't be embedded. Recorded as a {@link ParseWarning} on the include
     * directive rather than failing the including page.
     */
    private static final class UnresolvedIncludeException extends Exception {
        UnresolvedIncludeException(String message) {
            super(message);
        }
    }

    /**
     * A simple mutable cursor over the source text.
     */
    private static final class Scanner {
        final String s;
        final FileContext file;
        int pos;

        Scanner(String s, FileContext file) {
            this.s = s;
            this.file = file;
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
            // A request-time expression value, "<%= ... %>", runs to its %>: as in legacy containers
            // (and Jasper with strictQuoteEscaping=false), quotes inside the Java code
            // (value="<%= bean.get("x") %>") don't end the value.
            if (startsWith("<%=")) {
                int end = s.indexOf("%>", pos + 3);
                if (end >= 0 && end + 2 < s.length() && s.charAt(end + 2) == quote) {
                    pos = end + 3;
                    return s.substring(start, end + 2);
                }
            }
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
