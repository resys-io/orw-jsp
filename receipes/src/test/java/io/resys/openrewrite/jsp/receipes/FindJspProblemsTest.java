package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.receipes.table.JspProblems;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static io.resys.openrewrite.jsp.Assertions.jsp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class FindJspProblemsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindJspProblems(null, null));
    }

    @Test
    void wellFormedPageHasNoProblems() {
        rewriteRun(
                jsp(
                        """
                        <%@ taglib prefix="c" uri="jakarta.tags.core" %>
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta charset="UTF-8">
                            <script>if (a < b && c > d) { document.write("<div>"); }</script>
                            <style>p > span { color: red; }</style>
                        </head>
                        <body>
                        <!-- <div> in a comment is ignored -->
                        <p>first paragraph
                        <p>second paragraph<br>
                        <img src="x.png" alt="">
                        <ul><li>one<li>two</ul>
                        <table><tr><td>cell<td>cell</table>
                        <a href="<c:url value='/home'/>" title="${title}">home</a>
                        <input type="text" value="<%= value %>" />
                        <c:if test="${user != null}">
                            <div class="user">${user.name}</div>
                        </c:if>
                        <% if (admin) { %>
                            <div class="admin">admin</div>
                        <% } %>
                        </body>
                        </html>
                        """
                )
        );
    }

    @Test
    void missingEndTagImplicitlyClosed() {
        rewriteRun(
                jsp(
                        """
                        <div>
                            <span>text
                        </div>
                        """,
                        """
                        <div>
                            ~~(MISSING_END_TAG: <span> has no end tag (it is implicitly closed by </div> at line 3))~~><span>text
                        </div>
                        """
                )
        );
    }

    @Test
    void missingEndTagAtEndOfPage() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <div class="main">
                        """,
                        """
                        <p>intro</p>
                        ~~(MISSING_END_TAG: <div> has no end tag)~~><div class="main">
                        """
                )
        );
    }

    @Test
    void endTagWithoutStartTag() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        </span>
                        """,
                        """
                        <p>intro</p>
                        ~~(UNMATCHED_END_TAG: End tag </span> has no matching start tag)~~></span>
                        """
                )
        );
    }

    @Test
    void customTagWithoutEndTag() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <c:if test="${x}">
                            shown
                        """,
                        """
                        <p>intro</p>
                        ~~(MISSING_END_TAG: <c:if> has no end tag </c:if>)~~><c:if test="${x}">
                            shown
                        """
                )
        );
    }

    @Test
    void customEndTagWithoutStartTag() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        </c:if>
                        """,
                        """
                        <p>intro</p>
                        ~~(UNMATCHED_END_TAG: End tag </c:if> has no matching start tag)~~></c:if>
                        """
                )
        );
    }

    @Test
    void endTagsWithAttributes() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <div class="a">text</div class="a">
                        <c:if test="${x}">shown</c:if test="${x}">
                        """,
                        """
                        <p>intro</p>
                        <div class="a">text~~(END_TAG_WITH_ATTRIBUTES: End tag </div> has attributes or other content, which browsers ignore)~~></div class="a">
                        ~~(END_TAG_WITH_ATTRIBUTES: End tag </c:if> has attributes or other content)~~><c:if test="${x}">shown</c:if test="${x}">
                        """
                )
        );
    }

    @Test
    void scriptletInsideHtmlTag() {
        rewriteRun(
                jsp(
                        """
                        <select name="s">
                            <option <% if (selected) { %> selected="selected" <% } %>>One</option>
                        </select>
                        """,
                        """
                        <select name="s">
                            <option ~~(SCRIPTLET_IN_HTML_TAG: Java scriptlet inside HTML tag <option>: move the logic outside the tag, e.g. compute the attribute value first and output it with an expression)~~><% if (selected) { %> selected="selected" ~~(SCRIPTLET_IN_HTML_TAG: Java scriptlet inside HTML tag <option>: move the logic outside the tag, e.g. compute the attribute value first and output it with an expression)~~><% } %>>One</option>
                        </select>
                        """
                )
        );
    }

    @Test
    void scriptletInsideAttributeValue() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <div class="<% if (big) { %>big<% } %>">text</div>
                        """,
                        """
                        <p>intro</p>
                        <div class="~~(SCRIPTLET_IN_HTML_TAG: Java scriptlet inside HTML tag <div>: move the logic outside the tag, e.g. compute the attribute value first and output it with an expression)~~><% if (big) { %>big~~(SCRIPTLET_IN_HTML_TAG: Java scriptlet inside HTML tag <div>: move the logic outside the tag, e.g. compute the attribute value first and output it with an expression)~~><% } %>">text</div>
                        """
                )
        );
    }

    @Test
    void customTagInsideHtmlTag() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <option <c:if test="${sel}">selected</c:if>>One</option>
                        """,
                        """
                        <p>intro</p>
                        <option ~~(SCRIPTLET_IN_HTML_TAG: Custom tag <c:if> inside HTML tag <option>: move the logic outside the tag, e.g. compute the attribute value into a variable first)~~><c:if test="${sel}">selected</c:if>>One</option>
                        """
                )
        );
    }

    @Test
    void expressionGeneratingAttributes() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <input type="checkbox" <%= checked ? "checked" : "" %>>
                        <input type="checkbox" value="<%= v %>" ${disabled}>
                        """,
                        """
                        <p>intro</p>
                        <input type="checkbox" ~~(GENERATED_ATTRIBUTE: The expression generates attributes of HTML tag <input>: write the attribute out and compute only its value)~~><%= checked ? "checked" : "" %>>
                        <input type="checkbox" value="<%= v %>" ~~(GENERATED_ATTRIBUTE: The EL expression generates attributes of HTML tag <input>: write the attribute out and compute only its value)~~>${disabled}>
                        """
                )
        );
    }

    @Test
    void elementOpenedInBothBranchesOfJavaIf() {
        rewriteRun(
                jsp(
                        """
                        <% if (admin) { %>
                            <div class="admin">
                        <% } else { %>
                            <div class="user">
                        <% } %>
                            content
                        </div>
                        """,
                        """
                        <% if (admin) { %>
                            ~~(ELEMENT_CROSSES_BLOCK: <div> is opened inside the Java block opened at line 1 but not closed inside it: the page's structure then depends on which branch runs)~~><div class="admin">
                        <% } else { %>
                            ~~(ELEMENT_CROSSES_BLOCK: <div> is opened inside the Java block opened at line 3 but not closed inside it: the page's structure then depends on which branch runs)~~><div class="user">
                        <% } %>
                            content
                        </div>
                        """
                )
        );
    }

    @Test
    void elementClosedInsideCustomTagButOpenedOutside() {
        rewriteRun(
                jsp(
                        """
                        <div class="box">
                        <c:if test="${done}">
                            </div>
                        </c:if>
                        """,
                        """
                        <div class="box">
                        <c:if test="${done}">
                            ~~(ELEMENT_CROSSES_BLOCK: End tag </div> is inside <c:if> at line 2 but closes the <div> opened outside it at line 1: the page's structure then depends on which branch runs)~~></div>
                        </c:if>
                        """
                )
        );
    }

    @Test
    void htmlWrittenFromScriptlet() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <% for (String s : list) { out.println("<td>" + s + "</td>"); } %>
                        """,
                        """
                        <p>intro</p>
                        ~~(HTML_IN_SCRIPTLET: HTML markup written from Java string literals: it is invisible to readers and tools that read the template; write it as template text instead)~~><% for (String s : list) { out.println("<td>" + s + "</td>"); } %>
                        """
                )
        );
    }

    @Test
    void largeScriptlet() {
        rewriteRun(
                spec -> spec.recipe(new FindJspProblems(2, null)),
                jsp(
                        """
                        <p>intro</p>
                        <%
                            int a = 1;

                            int b = 2;
                            int c = a + b;
                        %>
                        """,
                        """
                        <p>intro</p>
                        ~~(LARGE_SCRIPTLET: Java scriptlet of 3 lines (more than 2): move the logic out of the page, e.g. into a servlet, bean, or tag)~~><%
                            int a = 1;

                            int b = 2;
                            int c = a + b;
                        %>
                        """
                )
        );
    }

    @Test
    void elementWithStartAndEndTagInDifferentIncludes() {
        // Balanced (so not MISSING_END_TAG / UNMATCHED_END_TAG), but split across files.
        rewriteRun(
                jsp(
                        """
                        <%@ include file="header.jspf" %>
                            content
                        <%@ include file="footer.jspf" %>
                        """,
                        """
                        <%@ include file="header.jspf" %>
                            content
                        ~~(TAG_SPLIT_ACROSS_FILES: End tag </div> in footer.jspf line 1 closes the <div> opened in header.jspf line 1: keep an element's start and end tag in the same file so each file is readable on its own (in footer.jspf))~~><%@ include file="footer.jspf" %>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp("<div class=\"page\">", spec -> spec.path("header.jspf")),
                jsp("</div>", spec -> spec.path("footer.jspf"))
        );
    }

    @Test
    void elementOpenedInIncludeAndClosedInPage() {
        rewriteRun(
                jsp(
                        """
                        <%@ include file="header.jspf" %>
                            content
                        </div>
                        """,
                        """
                        <%@ include file="header.jspf" %>
                            content
                        ~~(TAG_SPLIT_ACROSS_FILES: End tag </div> in index.jsp line 3 closes the <div> opened in header.jspf line 1: keep an element's start and end tag in the same file so each file is readable on its own)~~></div>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp("<div class=\"page\">", spec -> spec.path("header.jspf"))
        );
    }

    @Test
    void includeInsideHtmlTag() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <div <%@ include file="attrs.jspf" %>>text</div>
                        """,
                        """
                        <p>intro</p>
                        <div ~~(TAG_SPLIT_ACROSS_FILES: HTML tag <div> starting in index.jsp line 2 continues in included file attrs.jspf: keep each tag within one file)~~><%@ include file="attrs.jspf" %>>text</div>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp("class=\"box\"", spec -> spec.path("attrs.jspf"))
        );
    }

    @Test
    void includedFileEndingInsideHtmlTag() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <%@ include file="open.jspf" %> class="box">text</div>
                        """,
                        """
                        <p>intro</p>
                        ~~(TAG_SPLIT_ACROSS_FILES: HTML tag <div> starting in open.jspf line 1 is not finished there but continues in index.jsp after the include: keep each tag within one file)~~><%@ include file="open.jspf" %> class="box">text~~(TAG_SPLIT_ACROSS_FILES: End tag </div> in index.jsp line 2 closes the <div> opened in open.jspf line 1: keep an element's start and end tag in the same file so each file is readable on its own)~~></div>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp("<div", spec -> spec.path("open.jspf"))
        );
    }

    @Test
    void includesWithSelfContainedMarkupAreFine() {
        rewriteRun(
                jsp(
                        """
                        <%@ include file="header.jspf" %>
                        <div>content</div>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp("<header><h1>Title</h1></header>", spec -> spec.path("header.jspf"))
        );
    }

    @Test
    void unclosedElementInIncludedFileIsReportedOnTheIncludeDirective() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <%@ include file="header.jsp" %>
                        """,
                        """
                        <p>intro</p>
                        ~~(MISSING_END_TAG: <div> has no end tag (in header.jsp))~~><%@ include file="header.jsp" %>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                // A fragment by virtue of being included: not reported on its own.
                jsp("<div class=\"page\">", spec -> spec.path("header.jsp"))
        );
    }

    @Test
    void unresolvedIncludeSkipsBalanceChecks() {
        rewriteRun(
                jsp(
                        """
                        <div>
                        <%@ include file="missing.jspf" %>
                        """,
                        """
                        <div>
                        ~~(UNRESOLVED_INCLUDE: Included file 'missing.jspf' not found; tag balance is not checked for this page)~~><%@ include file="missing.jspf" %>
                        """,
                        spec -> spec.path("index.jsp")
                )
        );
    }

    @Test
    void problemsAreListedInDataTable() {
        rewriteRun(
                spec -> spec.dataTable(JspProblems.Row.class, rows -> {
                    assertThat(rows).extracting(JspProblems.Row::getSourcePath, JspProblems.Row::getFile,
                                    JspProblems.Row::getLine, JspProblems.Row::getRule)
                            .containsExactlyInAnyOrder(
                                    tuple("index.jsp", "header.jsp", 2, "MISSING_END_TAG"),
                                    tuple("index.jsp", "index.jsp", 4, "UNMATCHED_END_TAG"));
                }),
                jsp(
                        """
                        <p>intro</p>
                        <%@ include file="header.jsp" %>
                        <p>body</p>
                        </span>
                        """,
                        """
                        <p>intro</p>
                        ~~(MISSING_END_TAG: <section> has no end tag (in header.jsp))~~><%@ include file="header.jsp" %>
                        <p>body</p>
                        ~~(UNMATCHED_END_TAG: End tag </span> has no matching start tag)~~></span>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp(
                        """
                        <h1>title</h1>
                        <section>
                        """,
                        spec -> spec.path("header.jsp")
                )
        );
    }

    @Test
    void fragmentMayLeaveMarkupOpenOrCloseMarkupItDidNotOpen() {
        rewriteRun(
                jsp(
                        """
                        </div>
                        <div class="next">
                        """,
                        spec -> spec.path("parts/layout.jspf")
                )
        );
    }

    @Test
    void fragmentStillReportsLocalProblems() {
        rewriteRun(
                jsp(
                        """
                        <div class="next">
                            <option <% if (x) { %>selected<% } %>>a</option>
                        """,
                        """
                        <div class="next">
                            <option ~~(SCRIPTLET_IN_HTML_TAG: Java scriptlet inside HTML tag <option>: move the logic outside the tag, e.g. compute the attribute value first and output it with an expression)~~><% if (x) { %>selected~~(SCRIPTLET_IN_HTML_TAG: Java scriptlet inside HTML tag <option>: move the logic outside the tag, e.g. compute the attribute value first and output it with an expression)~~><% } %>>a</option>
                        """,
                        spec -> spec.path("parts/row.jspf")
                )
        );
    }

    @Test
    void scriptletsInsideHtmlCommentReportedOncePerComment() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <!-- old code:
                        <% int count = items.size(); %>
                        <%= count %>
                        -->
                        <!-- <c:out value="${x}"/> -->
                        """,
                        """
                        <p>intro</p>
                        ~~(JSP_IN_HTML_COMMENT: HTML comment <!-- --> contains a scriptlet <% %> at line 3, which is not commented out: it still runs on the server and its output is sent to the browser. Was a JSP comment <%-- --%> intended?)~~><!-- old code:
                        <% int count = items.size(); %>
                        <%= count %>
                        -->
                        ~~(JSP_IN_HTML_COMMENT: HTML comment <!-- --> contains a JSP tag <c:out> at line 6, which is not commented out: it still runs on the server and its output is sent to the browser. Was a JSP comment <%-- --%> intended?)~~><!-- <c:out value="${x}"/> -->
                        """
                )
        );
    }

    @Test
    void includeDirectiveInsideHtmlComment() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <!-- <%@ include file="old.jspf" %> -->
                        """,
                        """
                        <p>intro</p>
                        ~~(JSP_IN_HTML_COMMENT: HTML comment <!-- --> contains a directive <%@ include %> at line 2, which is not commented out: it still runs on the server and its output is sent to the browser. Was a JSP comment <%-- --%> intended?)~~><!-- <%@ include file="old.jspf" %> -->
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp("old", spec -> spec.path("old.jspf"))
        );
    }

    @Test
    void elInsideHtmlCommentAndJspCodeInsideJspCommentAreFine() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <!-- rendered for ${user.name} -->
                        <%-- <% int count = items.size(); %> <c:out value="${x}"/> --%>
                        """
                )
        );
    }

    @Test
    void splitBetweenIncludedFilesCanBeAllowed() {
        rewriteRun(
                spec -> spec.recipe(new FindJspProblems(null, true)),
                jsp(
                        """
                        <%@ include file="header.jspf" %>
                            content
                        <%@ include file="footer.jspf" %>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp("<div class=\"page\">", spec -> spec.path("header.jspf")),
                jsp("</div>", spec -> spec.path("footer.jspf"))
        );
    }

    @Test
    void allowedSplitStillReportsTagsInThePageMatchingAFragment() {
        // <div> header -> footer is allowed; <main> page -> footer and <section> header -> page are not.
        rewriteRun(
                spec -> spec.recipe(new FindJspProblems(null, true)),
                jsp(
                        """
                        <%@ include file="header.jspf" %>
                        </section>
                        <main>
                        <%@ include file="footer.jspf" %>
                        """,
                        """
                        <%@ include file="header.jspf" %>
                        ~~(TAG_SPLIT_ACROSS_FILES: End tag </section> in index.jsp line 2 closes the <section> opened in header.jspf line 2: keep an element's start and end tag in the same file so each file is readable on its own)~~></section>
                        <main>
                        ~~(TAG_SPLIT_ACROSS_FILES: End tag </main> in footer.jspf line 1 closes the <main> opened in index.jsp line 3: keep an element's start and end tag in the same file so each file is readable on its own (in footer.jspf))~~><%@ include file="footer.jspf" %>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp(
                        """
                        <div class="page">
                        <section>
                        """,
                        spec -> spec.path("header.jspf")
                ),
                jsp(
                        """
                        </main>
                        </div>
                        """,
                        spec -> spec.path("footer.jspf")
                )
        );
    }
}
