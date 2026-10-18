package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.JspIsoVisitor;
import io.resys.orw.jsp.tree.Jsp;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migrates pages served as XHTML ({@code application/xhtml+xml}) to HTML ({@code text/html}):
 * <ul>
 *     <li>The content type becomes {@code text/html}, keeping its parameters
 *     ({@code charset=UTF-8}), in {@code <%@ page contentType="..." %>}, in
 *     {@code <meta http-equiv="Content-Type" content="...">}, and in scriptlet calls to
 *     {@code response.setContentType("...")} and {@code setHeader("Content-Type", "...")}.</li>
 *     <li>The DOCTYPE becomes {@code <!DOCTYPE html>}, replacing XHTML's
 *     {@code <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "...">}.</li>
 *     <li>The XML declaration ({@code <?xml version="1.0" encoding="UTF-8"?>}), which HTML parses as a
 *     comment, is removed unless {@link #getRemoveXmlDeclaration()} is {@code false}.</li>
 *     <li>Void elements lose their self-closing syntax ({@code <br/>} and {@code <br />} become
 *     {@code <br>}) and their end tags ({@code <br></br>} becomes {@code <br>}), since HTML
 *     doesn't allow void elements to have an end tag.</li>
 *     <li>Other self-closed elements get an end tag ({@code <div class="x"/>} becomes
 *     {@code <div class="x"></div>}), because HTML ignores the {@code /} and would leave the element
 *     open.</li>
 * </ul>
 * JSP actions ({@code <c:out .../>}) aren't markup and keep their syntax. Self-closing tags inside
 * {@code <svg>} and {@code <math>} are valid HTML and are kept, as are the contents of comments,
 * CDATA sections, and {@code <script>}/{@code <style>} elements.
 * <p>
 * A page is migrated when its {@code contentType} (possibly declared in an included file) is
 * {@code application/xhtml+xml}, or, without one, when a scriptlet sets that content type. A file
 * that declares no content type itself, such as a {@code .jspf} fragment, is migrated when a page
 * migrated in the same run includes it and no page with another XML content type does. A fragment
 * no page includes is left alone.
 * <p>
 * The markup is scanned the way {@link JspPageAnalyzer} scans it: an XML tokenizer over the
 * {@link Jsp.Text} nodes in document order, with its state carried across the JSP constructs between
 * them, so {@code <input value="<%= v %>"/>} is recognized as one tag.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateXhtmlToHtml extends ScanningRecipe<MigrateXhtmlToHtml.Accumulator> {

    private static final String HTML_TYPE = "text/html";
    private static final String XHTML_TYPE = "application/xhtml+xml";
    private static final Pattern XHTML_TYPE_PATTERN = Pattern.compile(Pattern.quote(XHTML_TYPE), Pattern.CASE_INSENSITIVE);

    /**
     * {@code response.setContentType("application/xhtml+xml...")} and
     * {@code response.setHeader("Content-Type", "application/xhtml+xml...")}.
     */
    private static final Pattern SET_XHTML_CONTENT_TYPE = Pattern.compile(
            "(\\bsetContentType\\s*\\(\\s*\"|\\b(?:set|add)Header\\s*\\(\\s*\"Content-Type\"\\s*,\\s*\")" +
            Pattern.quote(XHTML_TYPE), Pattern.CASE_INSENSITIVE);

    String displayName = "Migrate XHTML pages to HTML";

    String description = "Changes pages served as `application/xhtml+xml` to `text/html`: updates the content " +
                          "type in the `page` directive, `<meta http-equiv=\"Content-Type\">` and " +
                          "`response.setContentType(...)`, replaces the XHTML DOCTYPE with `<!DOCTYPE html>`, " +
                          "removes the `<?xml ?>` declaration, removes self-closing syntax and end tags from void " +
                          "elements (`<br/>` and `<br></br>` become `<br>`), and gives other self-closed " +
                          "elements an end tag (`<div/>` becomes `<div></div>`).";

    @Option(displayName = "Remove XML declaration",
            description = "Remove the `<?xml version=\"1.0\" ...?>` declaration, which HTML parses as a comment. " +
                          "Defaults to `true`.",
            required = false)
    @Nullable
    Boolean removeXmlDeclaration;

    enum Kind {
        XHTML, OTHER_XML, HTML
    }

    public static class Accumulator {
        /**
         * Every file some page statically includes, with the kinds of the pages including it.
         */
        final Map<Path, Set<Kind>> includers = new HashMap<>();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JspIsoVisitor<ExecutionContext>() {
            @Override
            public Jsp.Directive visitDirective(Jsp.Directive directive, ExecutionContext ctx) {
                if (directive.getIncludedFile() != null) {
                    Kind kind = kind(getCursor().firstEnclosingOrThrow(Jsp.Document.class));
                    acc.includers.computeIfAbsent(directive.getIncludedFile().getSourcePath(),
                            p -> EnumSet.noneOf(Kind.class)).add(kind == null ? Kind.HTML : kind);
                }
                return super.visitDirective(directive, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new JspIsoVisitor<ExecutionContext>() {
            @Override
            public Jsp.Document visitDocument(Jsp.Document document, ExecutionContext ctx) {
                return isXhtml(document, acc) ? new Migration(!Boolean.FALSE.equals(removeXmlDeclaration)).migrate(document) : document;
            }
        };
    }

    private static boolean isXhtml(Jsp.Document document, Accumulator acc) {
        Kind kind = kind(document);
        if (kind != null) {
            return kind == Kind.XHTML;
        }
        Set<Kind> includers = acc.includers.get(document.getSourcePath());
        return includers != null && includers.contains(Kind.XHTML) && !includers.contains(Kind.OTHER_XML);
    }

    /**
     * @return what the page declares itself to be, or {@code null} if it declares no content type.
     */
    private static @Nullable Kind kind(Jsp.Document document) {
        String contentType = MarkupMode.declaredContentType(document);
        if (contentType == null) {
            return setsXhtmlContentType(document.getNodes()) ? Kind.XHTML : null;
        } else if (XHTML_TYPE.equals(MarkupMode.mimeType(contentType))) {
            return Kind.XHTML;
        }
        return MarkupMode.ofContentType(contentType) == MarkupMode.XML ? Kind.OTHER_XML : Kind.HTML;
    }

    private static boolean setsXhtmlContentType(List<Jsp.Content> nodes) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Scriptlet scriptlet && SET_XHTML_CONTENT_TYPE.matcher(scriptlet.getCode()).find()) {
                return true;
            } else if (node instanceof Jsp.Tag tag && tag.getBody() != null && setsXhtmlContentType(tag.getBody())) {
                return true;
            } else if (node instanceof Jsp.Directive directive && directive.getIncludedFile() != null &&
                       setsXhtmlContentType(directive.getIncludedFile().getNodes())) {
                return true;
            }
        }
        return false;
    }

    private static String htmlContentType(String contentType) {
        return XHTML_TYPE.equals(MarkupMode.mimeType(contentType)) ?
                XHTML_TYPE_PATTERN.matcher(contentType).replaceFirst(HTML_TYPE) : contentType;
    }

    private record Edit(int start, int end, String replacement) {
    }

    private enum State {
        DATA, IN_TAG, ATTR_VALUE, COMMENT, CDATA, DECLARATION, RAW_TEXT
    }

    /**
     * Migrates one file: {@link #scan} tokenizes its template text and records the edits per
     * {@link Jsp.Text} node, then {@link #rewrite} applies them. Included files' content is not
     * scanned, since it is migrated as a source file of its own.
     */
    private static final class Migration {
        private static final Set<String> RAW_TEXT_ELEMENTS = Set.of("script", "style");
        private static final String HTML_DOCTYPE = "<!DOCTYPE html>";
        private static final Pattern XHTML_DOCTYPE = Pattern.compile("<!DOCTYPE\\s+html\\s+(?:PUBLIC|SYSTEM)\\s[^\\[]*>",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern XML_DECLARATION = Pattern.compile("<\\?xml(?:\\s[^>]*)?\\?>");

        private final boolean removeXmlDeclaration;
        private final Map<UUID, List<Edit>> edits = new HashMap<>();

        private State state = State.DATA;
        private char quote;
        private String rawTextElement = "";

        /**
         * How many {@code <svg>}/{@code <math>} elements are open, inside which self-closing is valid HTML.
         */
        private int foreignDepth;

        private @Nullable UUID tagNode;
        private int tagStart;
        private String tagName = "";
        private boolean endTag;

        /**
         * Whether the current tag has no JSP construct in it, so it lies in the {@link #tagNode} text.
         */
        private boolean tagInOneNode;

        /**
         * Where a {@code /} followed only by whitespace so far was seen in the current tag, if anywhere.
         */
        private @Nullable UUID slashNode;
        private int slashOffset;

        Migration(boolean removeXmlDeclaration) {
            this.removeXmlDeclaration = removeXmlDeclaration;
        }

        Jsp.Document migrate(Jsp.Document document) {
            scan(document.getNodes());
            return document.withNodes(rewrite(document.getNodes()));
        }

        private void scan(List<Jsp.Content> nodes) {
            for (Jsp.Content node : nodes) {
                if (node instanceof Jsp.Text text) {
                    scan(text);
                    continue;
                }
                if (state == State.IN_TAG || state == State.ATTR_VALUE || state == State.DECLARATION) {
                    tagInOneNode = false;
                    slashNode = null;
                }
                if (node instanceof Jsp.Tag tag && tag.getBody() != null) {
                    scan(tag.getBody());
                }
            }
        }

        private void scan(Jsp.Text text) {
            String s = text.getText();
            UUID id = text.getId();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (state) {
                    case DATA:
                        if (c != '<') {
                            break;
                        }
                        if (s.startsWith("<!--", i)) {
                            state = State.COMMENT;
                            i += 3;
                        } else if (s.startsWith("<![CDATA[", i)) {
                            state = State.CDATA;
                            i += 8;
                        } else if (s.startsWith("<!", i) || s.startsWith("<?", i)) {
                            state = State.DECLARATION;
                            tagNode = id;
                            tagStart = i;
                            tagInOneNode = true;
                            i++;
                        } else {
                            boolean end = s.startsWith("</", i);
                            int nameStart = i + (end ? 2 : 1);
                            int nameEnd = nameEnd(s, nameStart);
                            if (nameEnd > nameStart && Character.isLetter(s.charAt(nameStart))) {
                                beginTag(id, i, s.substring(nameStart, nameEnd), end);
                                i = nameEnd - 1;
                            }
                        }
                        break;
                    case RAW_TEXT:
                        int nameStart = i + 2;
                        if (s.startsWith("</", i) &&
                            s.regionMatches(true, nameStart, rawTextElement, 0, rawTextElement.length()) &&
                            nameEnd(s, nameStart) == nameStart + rawTextElement.length()) {
                            beginTag(id, i, s.substring(nameStart, nameStart + rawTextElement.length()), true);
                            i = nameStart + rawTextElement.length() - 1;
                        }
                        break;
                    case COMMENT:
                        if (s.startsWith("-->", i)) {
                            state = State.DATA;
                            i += 2;
                        }
                        break;
                    case CDATA:
                        if (s.startsWith("]]>", i)) {
                            state = State.DATA;
                            i += 2;
                        }
                        break;
                    case DECLARATION:
                        if (c == '>') {
                            state = State.DATA;
                            if (tagInOneNode && id.equals(tagNode)) {
                                migrateDeclaration(s, id, i);
                            }
                        }
                        break;
                    case IN_TAG:
                        if (c == '"' || c == '\'') {
                            quote = c;
                            state = State.ATTR_VALUE;
                            slashNode = null;
                        } else if (c == '/') {
                            slashNode = id;
                            slashOffset = i;
                        } else if (c == '>') {
                            finishTag(s, id, i);
                        } else if (!Character.isWhitespace(c)) {
                            slashNode = null;
                        }
                        break;
                    case ATTR_VALUE:
                        if (c == quote) {
                            state = State.IN_TAG;
                        }
                        break;
                }
            }
        }

        private void beginTag(UUID node, int start, String name, boolean end) {
            state = State.IN_TAG;
            tagNode = node;
            tagStart = start;
            tagName = name;
            endTag = end;
            tagInOneNode = true;
            slashNode = null;
        }

        /**
         * @param s the text holding the tag's {@code >}, at {@code gt}
         */
        private void finishTag(String s, UUID node, int gt) {
            state = State.DATA;
            String name = tagName.toLowerCase(Locale.ROOT);
            boolean inOneNode = tagInOneNode && node.equals(tagNode);
            if (endTag) {
                if (JspPageAnalyzer.FOREIGN.contains(name) && foreignDepth > 0) {
                    foreignDepth--;
                } else if (foreignDepth == 0 && JspPageAnalyzer.VOID.contains(name) && inOneNode) {
                    edit(node, tagStart, gt + 1, "");
                }
                return;
            }

            boolean selfClosing = node.equals(slashNode);
            if (foreignDepth > 0 || JspPageAnalyzer.FOREIGN.contains(name)) {
                if (JspPageAnalyzer.FOREIGN.contains(name) && !selfClosing) {
                    foreignDepth++;
                }
                return;
            }
            if ("meta".equals(name) && inOneNode) {
                Matcher type = XHTML_TYPE_PATTERN.matcher(s).region(tagStart, gt);
                if (type.find()) {
                    edit(node, type.start(), type.end(), HTML_TYPE);
                }
            }
            if (selfClosing) {
                int from = slashOffset;
                while (from > 0 && Character.isWhitespace(s.charAt(from - 1))) {
                    from--;
                }
                if (JspPageAnalyzer.VOID.contains(name)) {
                    edit(node, from, gt, "");
                } else {
                    edit(node, from, gt + 1, "></" + tagName + ">");
                }
            } else if (RAW_TEXT_ELEMENTS.contains(name)) {
                state = State.RAW_TEXT;
                rawTextElement = name;
            }
        }

        /**
         * Replaces a DOCTYPE with a public or system identifier, such as XHTML's
         * {@code <!DOCTYPE html PUBLIC "..." "...">}, with HTML's {@code <!DOCTYPE html>}. A DOCTYPE with an
         * internal subset ({@code [...]}) declares entities and is left alone.
         * <p>
         * Removes an XML declaration ({@code <?xml version="1.0"?>}) with the line break after it, unless
         * {@link #removeXmlDeclaration} is off. Other processing instructions, such as
         * {@code <?xml-stylesheet ...?>}, are kept.
         *
         * @param s the text holding the declaration, which ends with the {@code >} at {@code gt}
         */
        private void migrateDeclaration(String s, UUID node, int gt) {
            if (XHTML_DOCTYPE.matcher(s).region(tagStart, gt + 1).matches()) {
                edit(node, tagStart, gt + 1, HTML_DOCTYPE);
            } else if (removeXmlDeclaration && XML_DECLARATION.matcher(s).region(tagStart, gt + 1).matches()) {
                int end = gt + 1;
                if (s.startsWith("\r\n", end)) {
                    end += 2;
                } else if (s.startsWith("\n", end)) {
                    end++;
                }
                edit(node, tagStart, end, "");
            }
        }

        private void edit(UUID node, int start, int end, String replacement) {
            edits.computeIfAbsent(node, n -> new ArrayList<>()).add(new Edit(start, end, replacement));
        }

        private static int nameEnd(String s, int from) {
            int i = from;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || "-_:.".indexOf(s.charAt(i)) >= 0)) {
                i++;
            }
            return i;
        }

        private List<Jsp.Content> rewrite(List<Jsp.Content> nodes) {
            return ListUtils.map(nodes, node -> {
                if (node instanceof Jsp.Text text) {
                    List<Edit> textEdits = edits.get(text.getId());
                    return textEdits == null ? text : text.withText(apply(text.getText(), textEdits));
                } else if (node instanceof Jsp.Tag tag && tag.getBody() != null) {
                    return tag.withBody(rewrite(tag.getBody()));
                } else if (node instanceof Jsp.Directive directive && "page".equals(directive.getName())) {
                    return directive.withAttributes(ListUtils.map(directive.getAttributes(), attribute -> {
                        if (!"contentType".equals(attribute.getName())) {
                            return attribute;
                        }
                        String value = attribute.getValue().getValue();
                        String html = htmlContentType(value);
                        return html.equals(value) ? attribute : attribute.withValue(attribute.getValue().withValue(html));
                    }));
                } else if (node instanceof Jsp.Scriptlet scriptlet) {
                    String code = SET_XHTML_CONTENT_TYPE.matcher(scriptlet.getCodeSource()).replaceAll("$1" + HTML_TYPE);
                    return code.equals(scriptlet.getCodeSource()) ? scriptlet : scriptlet.withCode(code);
                }
                return node;
            });
        }

        private static String apply(String text, List<Edit> textEdits) {
            StringBuilder result = new StringBuilder(text);
            textEdits.stream()
                    .sorted(Comparator.comparingInt(Edit::start).reversed())
                    .forEach(edit -> result.replace(edit.start(), edit.end(), edit.replacement()));
            return result.toString();
        }
    }
}
