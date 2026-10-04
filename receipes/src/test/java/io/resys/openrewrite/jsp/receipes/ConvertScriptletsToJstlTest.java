package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.receipes.table.JspScriptletConversions;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static io.resys.openrewrite.jsp.Assertions.jsp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.xml.Assertions.xml;

/**
 * RewriteTest runs each recipe a second time and fails if that changes anything again.
 */
class ConvertScriptletsToJstlTest implements RewriteTest {

    private static final String CORE = "<%@ taglib prefix=\"c\" uri=\"http://java.sun.com/jsp/jstl/core\" %>";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ConvertScriptletsToJstl(null, null));
    }

    @Test
    void ifElseChainBecomesChoose() {
        rewriteRun(
                spec -> spec.dataTable(JspScriptletConversions.Row.class, rows ->
                        assertThat(rows).extracting(JspScriptletConversions.Row::getLine,
                                        JspScriptletConversions.Row::getConstruct, JspScriptletConversions.Row::getStatus,
                                        JspScriptletConversions.Row::getDetail)
                                .containsExactly(
                                        tuple(3, "if/else", "CONVERTED",
                                                "${role == 'admin'} / ${param.guest != null} / otherwise"),
                                        // role's value comes from 'user', which EL can't see.
                                        tuple(2, "set", "SKIPPED", "value 'user.getRole()': 'user' is a Java variable " +
                                                                   "EL can't see (not mirrored into a page attribute)"))),
                jsp(
                        """
                        <%@ page contentType="text/html" %>
                        <% String role = user.getRole(); pageContext.setAttribute("role", role); %>
                        <% if (role.equals("admin")) { %>
                          <p>Admin</p>
                        <% } else if (request.getParameter("guest") != null) { %>
                          <p>Guest</p>
                        <% } else { %>
                          <p>User</p>
                        <% } %>
                        """,
                        CORE + "\n" + """
                        <%@ page contentType="text/html" %>
                        <% String role = user.getRole(); pageContext.setAttribute("role", role); %>
                        <c:choose><c:when test="${role == 'admin'}">
                          <p>Admin</p>
                        </c:when><c:when test="${param.guest != null}">
                          <p>Guest</p>
                        </c:when><c:otherwise>
                          <p>User</p>
                        </c:otherwise></c:choose>
                        """
                )
        );
    }

    @Test
    void loopsAndAssignmentsAfterVariableMigration() {
        // The output of MigrateJavaVariablesToPageAttributes(mirrorAll = true).
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <% List orders = (List) request.getAttribute("orders"); pageContext.setAttribute("orders", orders); %>
                        <% for (Order o : orders) { pageContext.setAttribute("o", o); %>
                          <td>${o.total}</td>
                        <% } %>
                        <% int count = 3; pageContext.setAttribute("count", count); %>
                        <% for (int i = 0; i < count; i++) { %>${i}<% } %>
                        """,
                        CORE + "\n" + """
                        <p>intro</p>
                        <c:set var="orders" value="${requestScope.orders}"/>
                        <c:forEach var="o" items="${orders}">
                          <td>${o.total}</td>
                        </c:forEach>
                        <c:set var="count" value="${3}"/>
                        <c:forEach var="i" begin="0" end="${count - 1}">${i}</c:forEach>
                        """
                )
        );
    }

    @Test
    void outputsBecomeEl() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <p><%= request.getParameter("q") %></p>
                        <p><%= a + b %></p>
                        """,
                        """
                        <p>intro</p>
                        <p>${param.q}</p>
                        <p><%= a + b %></p>
                        """
                )
        );
    }

    @Test
    void whatStaysJava() {
        rewriteRun(
                spec -> spec.recipe(new ConvertScriptletsToJstl(null, false))
                        .dataTable(JspScriptletConversions.Row.class, rows ->
                                assertThat(rows).extracting(JspScriptletConversions.Row::getConstruct,
                                                JspScriptletConversions.Row::getDetail)
                                        .containsExactly(
                                                tuple("for-each", "Java code in its body still uses 'o'"),
                                                tuple("if", "condition 'u.isActive()': method call 'isActive(...)' " +
                                                            "can't be translated"),
                                                tuple("if", "its closing } isn't in a scriptlet of its own at the same " +
                                                            "level (the block crosses a tag boundary, or other code " +
                                                            "is mixed in)"),
                                                tuple("if", "its opening scriptlet has code after the {"))),
                jsp(
                        """
                        <p>intro</p>
                        <% pageContext.setAttribute("orders", orders); %>
                        <% for (Order o : orders) { pageContext.setAttribute("o", o); %><% total += o.getTotal(); %><% } %>
                        <% pageContext.setAttribute("u", u); %><% if (u.isActive()) { %>active<% } %>
                        <% if (shown) { %><x:panel><% } %></x:panel>
                        <% if (shown) { log(); %>text<% } %>
                        """
                )
        );
    }

    @Test
    void usesAnExistingCorePrefix() {
        rewriteRun(
                jsp(
                        """
                        <%@ taglib prefix="core" uri="http://java.sun.com/jsp/jstl/core" %>
                        <% if (request.getParameter("debug") != null) { %>debug<% } %>
                        """,
                        """
                        <%@ taglib prefix="core" uri="http://java.sun.com/jsp/jstl/core" %>
                        <core:if test="${param.debug != null}">debug</core:if>
                        """
                )
        );
    }

    @Test
    void sizeAddsTheFunctionsLibrary() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <% if (session.getAttribute("cart") != null) { %>cart<% } %>
                        <%= ((java.util.List) request.getAttribute("items")).size() %>
                        """,
                        CORE + "\n<%@ taglib prefix=\"fn\" uri=\"http://java.sun.com/jsp/jstl/functions\" %>\n" + """
                        <p>intro</p>
                        <c:if test="${sessionScope.cart != null}">cart</c:if>
                        ${fn:length(requestScope.items)}
                        """
                )
        );
    }

    @Test
    void jstl10WritesOutputsWithCOutWhenContainerElIsOff() {
        rewriteRun(
                spec -> spec.recipe(new ConvertScriptletsToJstl("1.0", null)),
                xml("<web-app><display-name>legacy</display-name></web-app>", spec -> spec.path("WEB-INF/web.xml")),
                jsp(
                        """
                        <p>intro</p>
                        <% if (request.getParameter("q") != null) { %><%= request.getParameter("q") %><% } %>
                        """,
                        """
                        <%@ taglib prefix="c" uri="http://java.sun.com/jstl/core" %>
                        <p>intro</p>
                        <c:if test="${param.q != null}"><c:out value="${param.q}" escapeXml="false"/></c:if>
                        """
                )
        );
    }

    @Test
    void jstl12NeedsContainerEl() {
        rewriteRun(
                spec -> spec.dataTable(JspScriptletConversions.Row.class, rows ->
                        assertThat(rows).extracting(JspScriptletConversions.Row::getConstruct,
                                        JspScriptletConversions.Row::getStatus)
                                .containsExactly(tuple("page", "SKIPPED"))),
                xml("<web-app><display-name>legacy</display-name></web-app>", spec -> spec.path("WEB-INF/web.xml")),
                jsp(
                        """
                        <p>intro</p>
                        <% if (request.getParameter("q") != null) { %>q<% } %>
                        """
                )
        );
    }

    @Test
    void pipelineFromRawScriptletsToJstl() {
        // Step 3 (mirror every variable into a page attribute), then step 4, on an untouched page.
        rewriteRun(
                spec -> spec.recipes(new MigrateJavaVariablesToPageAttributes(null, true),
                        new ConvertScriptletsToJstl(null, null)),
                jsp(
                        """
                        <p>intro</p>
                        <% List orders = (List) request.getAttribute("orders"); %>
                        <% for (Order o : orders) { %>
                          <td><%= o.getTotal() %></td>
                        <% } %>
                        """,
                        CORE + "\n" + """
                        <p>intro</p>
                        <c:set var="orders" value="${requestScope.orders}"/>
                        <c:forEach var="o" items="${orders}">
                          <td>${o.total}</td>
                        </c:forEach>
                        """
                )
        );
    }
}
