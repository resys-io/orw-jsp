package io.resys.orw.jsp.receipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RewriteTest;

import static io.resys.orw.jsp.Assertions.jsp;

class RemoveUnusedImportsTest implements RewriteTest {

    @Override
    public void defaults(org.openrewrite.test.RecipeSpec spec) {
        spec.recipe(new RemoveUnusedImports());
    }

    @Test
    void removesWholeDirectiveWhenImportWasItsOnlyAttribute() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="text/html" %>
                        <%@ page import="java.util.Date" %>
                        <html></html>
                        """,
                        """
                        <%@ page contentType="text/html" %>

                        <html></html>
                        """
                )
        );
    }

    @Test
    void removesOnlyImportAttributeWhenOtherAttributesRemain() {
        rewriteRun(
                jsp(
                        """
                        <%@ page import="java.util.Date" contentType="text/html" %>
                        <html></html>
                        """,
                        """
                        <%@ page contentType="text/html" %>
                        <html></html>
                        """
                )
        );
    }

    @Test
    void dropsOnlyUnusedEntriesFromCommaList() {
        rewriteRun(
                jsp(
                        """
                        <%@ page import="java.util.Date, java.util.List, java.io.IOException" %>
                        <% List<String> names = new java.util.ArrayList<>(); %>
                        <%! private Date created; %>
                        """,
                        """
                        <%@ page import="java.util.Date, java.util.List" %>
                        <% List<String> names = new java.util.ArrayList<>(); %>
                        <%! private Date created; %>
                        """
                )
        );
    }

    @Test
    void classUsedOnlyInExpressionScriptletIsKept() {
        rewriteRun(
                jsp(
                        """
                        <%@ page import="java.util.Date" %>
                        <p><%= new Date() %></p>
                        """
                )
        );
    }

    @Test
    void classUsedInsideCustomTagBodyIsKept() {
        rewriteRun(
                jsp(
                        """
                        <%@ page import="java.util.Date" %>
                        <c:if test="${true}">
                            <%= new Date() %>
                        </c:if>
                        """
                )
        );
    }

    @Test
    void wildcardImportIsNeverRemoved() {
        rewriteRun(
                jsp(
                        """
                        <%@ page import="java.util.*" %>
                        <html>no scriptlets reference anything from java.util</html>
                        """
                )
        );
    }

    @Test
    void multiplePageDirectivesHandledIndependently() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="text/html" %>
                        <%@ page import="java.util.Date" %>
                        <%@ page import="java.util.List" %>
                        <% List<String> names; %>
                        """,
                        """
                        <%@ page contentType="text/html" %>

                        <%@ page import="java.util.List" %>
                        <% List<String> names; %>
                        """
                )
        );
    }

    @Test
    void noImportAttributeIsNoOp() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="text/html" %>
                        <html></html>
                        """
                )
        );
    }

    @Test
    void allImportsUsedIsNoOp() {
        rewriteRun(
                jsp(
                        """
                        <%@ page import="java.util.Date,java.util.List" %>
                        <% Date d = new Date(); List<String> l; %>
                        """
                )
        );
    }

    @Test
    void dedupesDuplicateEntryWithinSameCommaList() {
        rewriteRun(
                jsp(
                        """
                        <%@ page import="java.util.Date, java.util.List, java.util.Date" %>
                        <% Date d = new Date(); List<String> l; %>
                        """,
                        """
                        <%@ page import="java.util.Date, java.util.List" %>
                        <% Date d = new Date(); List<String> l; %>
                        """
                )
        );
    }

    @Test
    void dedupesDuplicateAcrossSeparatePageDirectives() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="text/html" %>
                        <%@ page import="java.util.Date" %>
                        <%@ page import="java.util.Date" %>
                        <% Date d = new Date(); %>
                        """,
                        """
                        <%@ page contentType="text/html" %>
                        <%@ page import="java.util.Date" %>

                        <% Date d = new Date(); %>
                        """
                )
        );
    }

    @Test
    void dedupesDuplicateWildcardImport() {
        rewriteRun(
                jsp(
                        """
                        <%@ page import="java.util.*" %>
                        <%@ page import="java.util.*" contentType="text/html" %>
                        <html></html>
                        """,
                        """
                        <%@ page import="java.util.*" %>
                        <%@ page contentType="text/html" %>
                        <html></html>
                        """
                )
        );
    }

    @Test
    void duplicateOfAnUnusedFirstOccurrenceIsStillDroppedAsUnused() {
        // The first occurrence is unused (so it's dropped, not "kept" for dedup purposes) - the
        // second occurrence is independently evaluated and is also unused, so it's dropped too,
        // rather than being kept just because it wasn't a literal duplicate of a *kept* entry.
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="text/html" %>
                        <%@ page import="java.util.Date" %>
                        <%@ page import="java.util.Date" %>
                        <html>no scriptlets at all</html>
                        """,
                        """
                        <%@ page contentType="text/html" %>


                        <html>no scriptlets at all</html>
                        """
                )
        );
    }

    @Test
    void keepsImportUsedOnlyInStaticallyIncludedFile() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="text/html" %>
                        <%@ page import="java.util.Date,java.util.List" %>
                        <%@ include file="includes/date.jspf" %>
                        """,
                        """
                        <%@ page contentType="text/html" %>
                        <%@ page import="java.util.Date" %>
                        <%@ include file="includes/date.jspf" %>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp("<%= new Date() %>", spec -> spec.path("includes/date.jspf"))
        );
    }

    @Test
    void skipsPageWithUnresolvedInclude() {
        // Date may well be used by the missing file, so nothing may be removed.
        rewriteRun(
                jsp(
                        """
                        <%@ page import="java.util.Date" %>
                        <%@ include file="includes/missing.jspf" %>
                        """,
                        spec -> spec.path("index.jsp")
                )
        );
    }
}
