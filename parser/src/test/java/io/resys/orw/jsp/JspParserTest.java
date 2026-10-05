package io.resys.orw.jsp;

import io.resys.orw.jsp.tree.Jsp;
import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.ParseExceptionResult;
import org.openrewrite.Parser;
import org.openrewrite.SourceFile;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.tree.ParseError;

import java.nio.file.Path;
import java.util.List;

import static io.resys.orw.jsp.Assertions.jsp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip (parse then print, expecting byte-for-byte identical output) tests covering the
 * "standard syntax" constructs defined by the Jakarta Server Pages 4.0 specification.
 */
class JspParserTest implements RewriteTest {

    @Test
    void plainHtmlIsPreservedAsText() {
        rewriteRun(
                jsp(
                        """
                        <!DOCTYPE html>
                        <html>
                        <head><title>Hello</title></head>
                        <body>
                        <p>Just some HTML, no JSP here.</p>
                        </body>
                        </html>
                        """
                )
        );
    }

    @Test
    void pageDirective() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="text/html;charset=UTF-8" language="java" %>
                        <html></html>
                        """
                )
        );
    }

    @Test
    void includeAndTaglibDirectives() {
        rewriteRun(
                jsp(
                        """
                        <%@ include file="header.jsp" %>
                        <%@ taglib prefix="c" uri="jakarta.tags.core" %>
                        """
                )
        );
    }

    @Test
    void jspComment() {
        rewriteRun(
                jsp(
                        """
                        <html>
                        <%-- this whole block, including <% code %> looking things, is just a comment --%>
                        </html>
                        """
                )
        );
    }

    @Test
    void scriptletDeclarationAndExpression() {
        rewriteRun(
                jsp(
                        """
                        <%! private int counter = 0; %>
                        <%
                            counter++;
                            String name = request.getParameter("name");
                        %>
                        <p>Hello, <%= name %>! You are visitor #<%= counter %>.</p>
                        """
                )
        );
    }
    @Test
    void invalidTagsAndStrangeScriplet() {
        List<SourceFile> parsed = JspParser.builder().build()
                .parseInputs(List.of(Parser.Input.fromString(Path.of("pages/strange.jsp"),
                        "<p>\n  <option:not <% %>></option>\n</p>\n")), null, new InMemoryExecutionContext())
                .toList();

        assertThat(parsed).singleElement().isInstanceOf(ParseError.class);
        assertThat(parsed.get(0).getMarkers().findFirst(ParseExceptionResult.class))
                .get()
                .extracting(ParseExceptionResult::getMessage)
                .asString()
                .contains("pages/strange.jsp:2:15: Malformed tag <option:not> (started at line 2, column 3):" +
                          " expected '>' or '/>' but found '<% %>></op'");
    }

    @Test
    void scriptletWithEscapedPercentGt() {
        rewriteRun(
                jsp(
                        """
                        <% String s = "100%\\>"; %>
                        """
                )
        );
    }

    @Test
    void expressionLanguageImmediateAndDeferred() {
        rewriteRun(
                jsp(
                        """
                        <p>${user.name}</p>
                        <p>#{user.email}</p>
                        <p>${empty list ? 'none' : list}</p>
                        """
                )
        );
    }

    @Test
    void expressionLanguageWithNestedBracesAndStringLiterals() {
        rewriteRun(
                jsp(
                        """
                        <p>${myMap['a}b'].get('x{y}z')}</p>
                        """
                )
        );
    }

    @Test
    void escapedElAndScriptletDelimitersInTemplateText() {
        rewriteRun(
                jsp(
                        """
                        <p>Price: \\${amount}, not EL. Tag: <\\% not a scriptlet %></p>
                        """
                )
        );
    }

    @Test
    void standardActionsUseBeanSetPropertyAndInclude() {
        rewriteRun(
                jsp(
                        """
                        <jsp:useBean id="user" class="com.example.User" scope="session"/>
                        <jsp:setProperty name="user" property="name" value="Ada"/>
                        <jsp:include page="footer.jsp">
                            <jsp:param name="year" value="2026"/>
                        </jsp:include>
                        """
                )
        );
    }

    @Test
    void customTagWithElInAttributeAndNestedBody() {
        rewriteRun(
                jsp(
                        """
                        <c:if test="${not empty user && user.age > 18}">
                            <c:forEach var="item" items="${items}">
                                <p>${item.name}</p>
                            </c:forEach>
                        </c:if>
                        """
                )
        );
    }

    @Test
    void attributeValueContainingEscapedQuote() {
        rewriteRun(
                jsp(
                        """
                        <c:out value="she said \\"hi\\"" default="none"/>
                        """
                )
        );
    }

    @Test
    void fullPageMixingEverything() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="text/html;charset=UTF-8" %>
                        <%@ taglib prefix="c" uri="jakarta.tags.core" %>
                        <%!
                            private static int visits = 0;
                        %>
                        <%-- track visits --%>
                        <% visits++; %>
                        <!DOCTYPE html>
                        <html>
                        <body>
                            <h1>Welcome, ${sessionScope.user.name}!</h1>
                            <c:if test="${visits > 1}">
                                <p>You've been here <%= visits %> times.</p>
                            </c:if>
                            <c:forEach var="p" items="${products}">
                                <div class="product">${p.name} - ${p.price}</div>
                            </c:forEach>
                        </body>
                        </html>
                        """
                )
        );
    }

    @Test
    void unclosedCustomTagAtEndOfInputIsRecovered() {
        rewriteRun(
                jsp(
                        """
                        <c:if test="${x}">
                            <p>never closed</p>
                        """,
                        spec -> spec.afterRecipe(document -> {
                            Jsp.Tag tag = (Jsp.Tag) document.getNodes().get(0);
                            assertThat(tag.isSelfClosing()).isFalse();
                            assertThat(tag.getClosing()).isNull();
                        })
                )
        );
    }

    @Test
    void unclosedCustomTagEndsAtEnclosingEndTag() {
        rewriteRun(
                jsp(
                        """
                        <c:forEach var="i" items="${list}">
                            <c:if test="${i > 0}">
                                ${i}
                        </c:forEach>
                        """,
                        spec -> spec.afterRecipe(document -> {
                            Jsp.Tag forEach = (Jsp.Tag) document.getNodes().get(0);
                            assertThat(forEach.getClosing()).isNotNull();
                            Jsp.Tag inner = (Jsp.Tag) forEach.getBody().get(1);
                            assertThat(inner.getName()).isEqualTo("c:if");
                            assertThat(inner.getClosing()).isNull();
                        })
                )
        );
    }

    @Test
    void endTagWithAttributesIsTolerated() {
        rewriteRun(
                jsp(
                        """
                        <c:if test="${x}">shown</c:if test="${x > 1}">
                        """,
                        spec -> spec.afterRecipe(document -> {
                            Jsp.Tag tag = (Jsp.Tag) document.getNodes().get(0);
                            assertThat(tag.getClosing().getBeforeTagDelimiterPrefix()).isEqualTo(" test=\"${x > 1}\"");
                        })
                )
        );
    }

    @Test
    void requestTimeAttributeValueWithQuotesInsideTheExpression() {
        // Common in Struts 1 pages; legacy containers (and Jasper with strictQuoteEscaping=false) read
        // such a value up to its %>.
        rewriteRun(
                jsp(
                        """
                        <html:text property="name" value="<%= bean.get("name") %>"/>
                        <html:text property="x" value='<%= map.get('k') %>'/>
                        """,
                        spec -> spec.afterRecipe(document -> {
                            Jsp.Tag tag = (Jsp.Tag) document.getNodes().get(0);
                            assertThat(tag.getAttributes().get(1).getValue().getValue())
                                    .isEqualTo("<%= bean.get(\"name\") %>");
                        })
                )
        );
    }
}
