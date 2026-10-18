package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.JspIsoVisitor;
import io.resys.orw.jsp.receipes.table.JspProblems;
import io.resys.orw.jsp.tree.Jsp;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds structural and readability problems in JSP pages, marking each one in the source with a
 * {@link org.openrewrite.marker.SearchResult} and listing them all in the {@link JspProblems}
 * data table. Nothing is changed.
 * <p>
 * Reported problems (see {@link JspPageAnalyzer.Rule}):
 * <ul>
 *     <li>{@code MISSING_END_TAG} - an HTML element whose end tag is mandatory, or a JSP action, is
 *     never closed (or is only implicitly closed by an enclosing element's end tag).</li>
 *     <li>{@code UNMATCHED_END_TAG} - an end tag with no start tag.</li>
 *     <li>{@code END_TAG_WITH_ATTRIBUTES} - e.g. {@code </div class="x">}.</li>
 *     <li>{@code SCRIPTLET_IN_HTML_TAG} - Java code or a custom tag with a body inside an HTML start
 *     tag, e.g. {@code <option <% if (sel) { %>selected<% } %>>}.</li>
 *     <li>{@code ELEMENT_CROSSES_BLOCK} - an HTML element opened inside a JSP action body or a Java
 *     block and closed outside it (or the reverse), e.g. a {@code <div>} opened in both branches of
 *     an {@code if}/{@code else} but closed once after it.</li>
 *     <li>{@code GENERATED_ATTRIBUTE} - an expression that outputs attributes rather than an
 *     attribute value, e.g. {@code <input <%= checked ? "checked" : "" %>>}.</li>
 *     <li>{@code HTML_IN_SCRIPTLET} - markup written from Java, e.g. {@code out.print("<td>")}.</li>
 *     <li>{@code LARGE_SCRIPTLET} - a scriptlet or declaration over {@link #getMaxScriptletLines()}
 *     non-blank lines.</li>
 *     <li>{@code JSP_IN_HTML_COMMENT} - a scriptlet, expression, declaration, directive, or JSP tag
 *     inside an HTML comment {@code <!-- -->}, where it still runs (only its output is commented
 *     out); usually a JSP comment {@code <%-- --%>} was intended. Reported once per comment.</li>
 *     <li>{@code TAG_SPLIT_ACROSS_FILES} - an element whose start and end tags are in different
 *     files (e.g. {@code <div>} in a header include, {@code </div>} in a footer include), or a
 *     single tag split by an include ({@code <div <%@ include file="attrs.jspf" %>>}, or an
 *     included file that ends mid-tag). With {@link #getAllowSplitBetweenIncludedFiles()}, an element
 *     whose start and end tags are both in included files is not reported.</li>
 *     <li>{@code VARIABLE_FROM_INCLUDE} - the page uses a variable defined only in a file it statically
 *     includes: a Java variable declared at the top level of a scriptlet, a field or method from a
 *     {@code <%! %>} declaration, a {@code <jsp:useBean>}, or a scoped attribute
 *     ({@code <c:set var>} and other {@code var}-exporting tags, {@code request.setAttribute(...)}),
 *     read from Java, EL, {@code getAttribute("...")}, or {@code <jsp:getProperty name>}. Reported once
 *     per variable, at its first use after the include. A page that defines the name itself, or a
 *     variable only visible inside a block or a {@code <c:forEach>} body, is not reported.</li>
 *     <li>{@code UNKNOWN_TAG}, {@code UNKNOWN_ATTRIBUTE}, {@code MISSING_REQUIRED_ATTRIBUTE},
 *     {@code INVALID_TAG_BODY} (a body on a {@code body-content="empty"} tag, or scripting in a
 *     {@code scriptless} one), {@code UNKNOWN_EL_FUNCTION} - a custom tag or EL function call that
 *     doesn't match its library's tag library descriptor (TLD), with a "did you mean" suggestion
 *     for near misses. Only checked for taglibs whose TLD the parser resolved (see
 *     {@link io.resys.orw.jsp.JspParser.Builder#taglib} and
 *     {@link io.resys.orw.jsp.JspParser.Builder#tldSearchPath}); a TLD's {@code <variable>}
 *     declarations also make {@code VARIABLE_FROM_INCLUDE} exact for its tags.</li>
 *     <li>{@code UNRESOLVED_INCLUDE} - a static include that could not be resolved; tag balance is
 *     then not checked for the page.</li>
 * </ul>
 * Statically included files are analyzed as part of each page that includes them, so markup
 * opened in a header include and closed in a footer include is balanced (though reported as
 * {@code TAG_SPLIT_ACROSS_FILES}). Fragments - {@code .jspf}
 * files, and any file another page includes - are not reported for markup left open at their end
 * or closed without being opened in them, since their includers may legitimately do that.
 * <p>
 * Markup is checked as HTML or as XML ({@link MarkupMode}), as chosen by {@link #getMarkupMode()}.
 * By default the page's {@code <%@ page contentType="..." %>} decides: an XML type such as
 * {@code application/xhtml+xml} or {@code text/xml} means XML, anything else (including no
 * {@code contentType}, which is {@code text/html}) means HTML. A fragment without its own
 * {@code contentType} is XML if every page including it is. In XML, names are case-sensitive and
 * every element must be closed or self-closed, so e.g. {@code <br>}, {@code <p>} without
 * {@code </p>} and {@code <Div></div>} are reported, and {@code <script>} content is markup
 * (use a CDATA section for raw text).
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class FindJspProblems extends ScanningRecipe<FindJspProblems.Accumulator> {

    private static final int DEFAULT_MAX_SCRIPTLET_LINES = 20;

    transient JspProblems problems = new JspProblems(this);

    String displayName = "Find JSP problems";

    String description = "Finds unbalanced tags (missing end tags, end tags without a start tag, end tags " +
                          "with attributes), Java scriptlets and custom tags inside HTML tags, markup whose " +
                          "structure depends on which branch of a scriptlet or `<c:if>` runs, JSP code inside " +
                          "HTML comments (where it still runs), pages using variables defined in an included " +
                          "file, and other " +
                          "constructs that make JSP pages hard to read.";

    @Option(displayName = "Maximum scriptlet lines",
            description = "Scriptlets and declarations with more non-blank lines than this are reported. " +
                          "Defaults to " + DEFAULT_MAX_SCRIPTLET_LINES + ".",
            example = "20",
            required = false)
    @Nullable
    Integer maxScriptletLines;

    @Option(displayName = "Allow elements split between included files",
            description = "Don't report an element whose start and end tags are both in included files but " +
                          "different ones, e.g. `<div>` opened in `header.jspf` and closed in `footer.jspf`. " +
                          "Splits involving the page itself (start tag in an included file, end tag in the " +
                          "page, or the reverse) and single tags cut by an include are still reported. " +
                          "Defaults to `false`.",
            required = false)
    @Nullable
    Boolean allowSplitBetweenIncludedFiles;

    @Option(displayName = "Markup mode",
            description = "Whether template text is checked as HTML or XML. `auto` (the default) uses " +
                          "the page's `contentType`: XML for `text/xml`, `application/xml` and `+xml` types " +
                          "such as `application/xhtml+xml`, otherwise HTML. A fragment without a " +
                          "`contentType` of its own is XML if all pages including it are. In XML, every " +
                          "element must be closed or self-closed (`<br/>`), names are case-sensitive, and " +
                          "there are no optional end tags or raw-text elements.",
            valid = {"auto", "html", "xml"},
            example = "auto",
            required = false)
    @Nullable
    String markupMode;

    public static class Accumulator {
        /**
         * Every file some page statically includes, with the markup modes of the pages including it.
         */
        final Map<Path, Set<MarkupMode>> includedFiles = new HashMap<>();
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
                    Jsp.Document page = getCursor().firstEnclosingOrThrow(Jsp.Document.class);
                    MarkupMode declared = MarkupMode.declared(page);
                    acc.includedFiles.computeIfAbsent(directive.getIncludedFile().getSourcePath(),
                            p -> EnumSet.noneOf(MarkupMode.class)).add(declared == null ? MarkupMode.HTML : declared);
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
                Path path = document.getSourcePath();
                boolean fragment = path.toString().endsWith(".jspf") || acc.includedFiles.containsKey(path);
                JspPageAnalyzer analyzer = new JspPageAnalyzer(document, fragment, markupMode(document, acc),
                        maxScriptletLines == null ? DEFAULT_MAX_SCRIPTLET_LINES : maxScriptletLines,
                        Boolean.TRUE.equals(allowSplitBetweenIncludedFiles));
                List<JspPageAnalyzer.Problem> found = analyzer.analyze(document);
                for (JspPageAnalyzer.Problem problem : found) {
                    problems.insertRow(ctx, new JspProblems.Row(path.toString(), problem.file().toString(),
                            problem.line(), problem.rule().name(), problem.message()));
                }
                return analyzer.annotate(document);
            }
        };
    }

    private MarkupMode markupMode(Jsp.Document document, Accumulator acc) {
        if ("html".equalsIgnoreCase(markupMode)) {
            return MarkupMode.HTML;
        } else if ("xml".equalsIgnoreCase(markupMode)) {
            return MarkupMode.XML;
        }
        MarkupMode declared = MarkupMode.declared(document);
        if (declared != null) {
            return declared;
        }
        Set<MarkupMode> includers = acc.includedFiles.get(document.getSourcePath());
        return includers != null && includers.equals(EnumSet.of(MarkupMode.XML)) ? MarkupMode.XML : MarkupMode.HTML;
    }
}
