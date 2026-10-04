package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.receipes.table.JspConstructTotals;
import io.resys.openrewrite.jsp.receipes.table.JspConstructUsage;
import io.resys.openrewrite.jsp.receipes.table.JspPageInventory;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static io.resys.openrewrite.jsp.Assertions.jsp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class JspInventoryTest implements RewriteTest {

    private static final String HTML = "http://struts.apache.org/tags-html";
    private static final String BEAN = "http://struts.apache.org/tags-bean";
    private static final String LOGIC = "http://struts.apache.org/tags-logic";
    private static final String FN = "jakarta.tags.functions";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new JspInventory());
    }

    @Test
    void inventoryOfStrutsPages() {
        rewriteRun(
                spec -> spec
                        .dataTable(JspConstructTotals.Row.class, rows -> {
                            // Most used first: taglib directives, 5 across 3 files.
                            assertThat(rows.get(0)).extracting(JspConstructTotals.Row::getCategory,
                                            JspConstructTotals.Row::getConstruct, JspConstructTotals.Row::getTotalCount,
                                            JspConstructTotals.Row::getPageCount)
                                    .containsExactly("DIRECTIVE", "taglib", 5, 3);
                            assertThat(rows).extracting(JspConstructTotals.Row::getCategory,
                                            JspConstructTotals.Row::getConstruct, JspConstructTotals.Row::getLibrary,
                                            JspConstructTotals.Row::getTotalCount, JspConstructTotals.Row::getPageCount)
                                    .contains(
                                            // Same library under prefixes "html" and "h": counted together.
                                            tuple("TAG", "html:form", HTML, 2, 2),
                                            tuple("TAG", "bean:write", BEAN, 2, 1),
                                            // Prefix declared in the included common.jspf.
                                            tuple("TAG", "logic:iterate", LOGIC, 1, 1),
                                            tuple("TAG", "jsp:include", JspInventoryCollector.STANDARD_ACTION, 1, 1),
                                            tuple("TAG", "x:undeclared", JspInventoryCollector.UNDECLARED, 1, 1),
                                            tuple("EL_FUNCTION", "fn:length", FN, 1, 1),
                                            tuple("EL_IMPLICIT_OBJECT", "sessionScope", "", 1, 1),
                                            tuple("SCRIPTING", "scriptlet", "", 2, 2),
                                            tuple("SCRIPTING", "expression in tag attribute", "", 1, 1),
                                            tuple("JAVA_IMPLICIT_OBJECT", "request", "", 1, 1),
                                            tuple("JAVA_IMPLICIT_OBJECT", "session", "", 1, 1));
                            for (int i = 1; i < rows.size(); i++) {
                                assertThat(rows.get(i).getTotalCount()).isLessThanOrEqualTo(rows.get(i - 1).getTotalCount());
                            }
                        })
                        .dataTable(JspConstructUsage.Row.class, rows ->
                                assertThat(rows).extracting(JspConstructUsage.Row::getSourcePath,
                                                JspConstructUsage.Row::getConstruct, JspConstructUsage.Row::getLibrary,
                                                JspConstructUsage.Row::getCount)
                                        .contains(
                                                tuple("index.jsp", "html:form", HTML, 1),
                                                tuple("other.jsp", "h:form", HTML, 1),
                                                tuple("index.jsp", "bean:write", BEAN, 2),
                                                // The included file's own taglibs count for it, not for index.jsp.
                                                tuple("common.jspf", "taglib", "", 2),
                                                tuple("index.jsp", "taglib", "", 2)))
                        .dataTable(JspPageInventory.Row.class, rows ->
                                assertThat(rows).filteredOn(row -> row.getSourcePath().equals("index.jsp"))
                                        .singleElement()
                                        .satisfies(row -> {
                                            assertThat(row.getLines()).isEqualTo(15);
                                            assertThat(row.getScriptlets()).isEqualTo(1);
                                            assertThat(row.getJavaLines()).isEqualTo(2); // blank line not counted
                                            assertThat(row.getExpressions()).isEqualTo(1);
                                            assertThat(row.getDeclarations()).isZero();
                                            assertThat(row.getElExpressions()).isEqualTo(2);
                                            // html:form, html:text, bean:write x2, logic:iterate, x:undeclared
                                            assertThat(row.getCustomTags()).isEqualTo(6);
                                            // <%@ include %> and <jsp:include>
                                            assertThat(row.getIncludes()).isEqualTo(2);
                                            assertThat(row.getTagLibraries()).isEqualTo(HTML + ", " + BEAN);
                                        })),
                jsp(
                        """
                        <%@ page import="java.util.List" %>
                        <%@ taglib prefix="html" uri="http://struts.apache.org/tags-html" %>
                        <%@ taglib prefix="bean" uri="http://struts.apache.org/tags-bean" %>
                        <%@ include file="common.jspf" %>
                        <html:form action="/save">
                            <html:text property="name" value="<%= user.getName() %>"/>
                            <bean:write name="user" property="email"/>
                        </html:form>
                        <%
                            List items = (List) request.getAttribute("items");

                            int count = items.size();
                        %>
                        <logic:iterate id="item" name="items"><bean:write name="item"/></logic:iterate><x:undeclared/>
                        ${fn:length(items)} ${sessionScope.user}<jsp:include page="footer.jsp"/>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp(
                        """
                        <%@ taglib prefix="logic" uri="http://struts.apache.org/tags-logic" %>
                        <%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
                        """,
                        spec -> spec.path("common.jspf")
                ),
                jsp(
                        """
                        <%@ taglib prefix="h" uri="http://struts.apache.org/tags-html" %>
                        <h:form action="/logout"></h:form>
                        <% session.invalidate(); %>
                        """,
                        spec -> spec.path("other.jsp")
                )
        );
    }
}
