package io.resys.openrewrite.jsp.receipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RewriteTest;

import static io.resys.openrewrite.jsp.Assertions.jsp;

class RemoveUnusedTaglibsTest implements RewriteTest {

    @Override
    public void defaults(org.openrewrite.test.RecipeSpec spec) {
        spec.recipe(new RemoveUnusedTaglibs());
    }

    @Test
    void removesTaglibNeverReferenced() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="text/html" %>
                        <%@ taglib prefix="unused" uri="http://example.com/unused" %>
                        <html><body>no custom tags here</body></html>
                        """,
                        """
                        <%@ page contentType="text/html" %>

                        <html><body>no custom tags here</body></html>
                        """
                )
        );
    }

    @Test
    void keepsTaglibUsedAsCustomTag() {
        rewriteRun(
                jsp(
                        """
                        <%@ taglib prefix="c" uri="jakarta.tags.core" %>
                        <c:if test="${true}">
                            <p>shown</p>
                        </c:if>
                        """
                )
        );
    }

    @Test
    void keepsTaglibUsedOnlyAsElFunction() {
        // The JSTL functions library ("fn") is only ever invoked from EL, never as a <fn:...> element -
        // this is the case that a naive "scan for <prefix:tag>" implementation would get wrong.
        rewriteRun(
                jsp(
                        """
                        <%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
                        <p>${fn:length(items)}</p>
                        """
                )
        );
    }

    @Test
    void keepsTaglibUsedOnlyInsideAttributeValueEl() {
        rewriteRun(
                jsp(
                        """
                        <%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
                        <a href="${fn:escapeXml(url)}">link</a>
                        """
                )
        );
    }

    @Test
    void keepsTaglibUsedOnlyInsideNestedTagBody() {
        rewriteRun(
                jsp(
                        """
                        <%@ taglib prefix="c" uri="jakarta.tags.core" %>
                        <%@ taglib prefix="x" uri="http://example.com/x" %>
                        <c:forEach var="i" items="${items}">
                            <x:widget id="${i}"/>
                        </c:forEach>
                        """
                )
        );
    }

    @Test
    void removesOnlyUnusedTaglibsAmongSeveral() {
        rewriteRun(
                jsp(
                        """
                        <%@ taglib prefix="c" uri="jakarta.tags.core" %>
                        <%@ taglib prefix="unused" uri="http://example.com/unused" %>
                        <%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
                        <c:if test="${fn:length(items) > 0}">
                            <p>has items</p>
                        </c:if>
                        """,
                        """
                        <%@ taglib prefix="c" uri="jakarta.tags.core" %>

                        <%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
                        <c:if test="${fn:length(items) > 0}">
                            <p>has items</p>
                        </c:if>
                        """
                )
        );
    }

    @Test
    void noTaglibDirectivesIsNoOp() {
        rewriteRun(
                jsp(
                        """
                        <html><body>plain page</body></html>
                        """
                )
        );
    }
}
