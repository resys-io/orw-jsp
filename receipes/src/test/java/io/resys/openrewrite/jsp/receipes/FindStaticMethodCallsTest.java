package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.receipes.table.JspStaticMethodCalls;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.List;

import static io.resys.openrewrite.jsp.Assertions.jsp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class FindStaticMethodCallsTest implements RewriteTest {

    private static final String PAGE = """
            <%@ page import="com.acme.util.DateUtils, java.util.*" %>
            <p>intro</p>
            <%
                String today = DateUtils.format(new Date());
                int n = Integer.parseInt(request.getParameter("n"));
                List<String> l = Collections.<String>emptyList();
                String s = "Fake.call()"; // Comment.call()
                String name = Status.ACTIVE.name();
                Object o = new Outer.Inner();
            %>
            <p><%= com.acme.Formatter.money(total) %></p>
            <html:text property="x" value="<%= StringUtils.trim(v) %>"/>
            """;

    private static final String MARKED = """
            <%@ page import="com.acme.util.DateUtils, java.util.*" %>
            <p>intro</p>
            ~~(Static method call com.acme.util.DateUtils.format(), Static method call java.lang.Integer.parseInt(), Static method call Collections.emptyList())~~><%
                String today = DateUtils.format(new Date());
                int n = Integer.parseInt(request.getParameter("n"));
                List<String> l = Collections.<String>emptyList();
                String s = "Fake.call()"; // Comment.call()
                String name = Status.ACTIVE.name();
                Object o = new Outer.Inner();
            %>
            <p>~~(Static method call com.acme.Formatter.money())~~><%= com.acme.Formatter.money(total) %></p>
            <html:text property="x" ~~(Static method call StringUtils.trim())~~>value="<%= StringUtils.trim(v) %>"/>
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindStaticMethodCalls(null));
    }

    @Test
    void marksStaticCalls() {
        rewriteRun(
                jsp(PAGE, MARKED)
        );
    }

    @Test
    void listsCallsWithLinesAndCandidates() {
        rewriteRun(
                spec -> spec.dataTable(JspStaticMethodCalls.Row.class, rows ->
                        assertThat(rows).extracting(JspStaticMethodCalls.Row::getLine,
                                        JspStaticMethodCalls.Row::getClassName, JspStaticMethodCalls.Row::getMethod,
                                        JspStaticMethodCalls.Row::getCandidates, JspStaticMethodCalls.Row::getContext)
                                .containsExactly(
                                        tuple(4, "com.acme.util.DateUtils", "format", "", "scriptlet"),
                                        tuple(5, "java.lang.Integer", "parseInt", "", "scriptlet"),
                                        // java.util.* is a wildcard import: can't tell for sure.
                                        tuple(6, "Collections", "emptyList", "java.util.Collections, Collections",
                                                "scriptlet"),
                                        tuple(11, "com.acme.Formatter", "money", "", "expression"),
                                        tuple(12, "StringUtils", "trim", "java.util.StringUtils, StringUtils",
                                                "tag attribute"))),
                jsp(PAGE, MARKED, spec -> spec.path("index.jsp"))
        );
    }

    @Test
    void exclusions() {
        rewriteRun(
                spec -> spec.recipe(new FindStaticMethodCalls(List.of(
                                "java.lang.Integer.parseInt", "com.acme.util.*.format", "com.acme.**", "java.util.Collections.*")))
                        .dataTable(JspStaticMethodCalls.Row.class, rows ->
                                // StringUtils is also a candidate for java.util.StringUtils, but no pattern matches it.
                                assertThat(rows).extracting(JspStaticMethodCalls.Row::getClassName,
                                                JspStaticMethodCalls.Row::getMethod)
                                        .containsExactly(tuple("StringUtils", "trim"))),
                jsp(PAGE, PAGE.replace("value=\"<%= StringUtils", "~~(Static method call StringUtils.trim())~~>value=\"<%= StringUtils"))
        );
    }

    @Test
    void capitalizedLocalVariablesAndIncludedFilesAreNotReportedForThePage() {
        rewriteRun(
                jsp(
                        """
                        <%@ include file="helpers.jspf" %>
                        <% Runner Helper = new Runner(); Helper.run(); %>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                // Reported for the included file itself.
                jsp(
                        "<% Util.go(); %>",
                        "~~(Static method call Util.go())~~><% Util.go(); %>",
                        spec -> spec.path("helpers.jspf")
                )
        );
    }
}
