package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.receipes.table.JspManualMigrations;
import io.resys.orw.jsp.receipes.table.JspMigrationEffort;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static io.resys.orw.jsp.Assertions.jsp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class FindUnmigratableScriptletsTest implements RewriteTest {

    private static final String PAGE = """
            <%@ page import="java.util.*" %>
            <%! private int hits = 0; %>
            <% List orders = (List) request.getAttribute("orders"); %>
            <% for (Order o : orders) { %>
              <td><%= o.getTotal() %></td>
            <% } %>
            <% Connection con = dataSource.getConnection(); %>
            <% if (user.isAdmin()) { %>admin<% } %>
            <% session.setAttribute("seen", Boolean.TRUE); %>
            <% if (expired) { response.sendRedirect("login.jsp"); return; } %>
            <%= DateUtils.format(now) %>
            <% out.println("<br>"); %>
            <html:text property="x" value="<%= request.getParameter("x") %>"/>
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindUnmigratableScriptlets(null));
    }

    @Test
    void classifiesEveryJavaConstruct() {
        rewriteRun(
                spec -> spec
                        .dataTable(JspManualMigrations.Row.class, rows -> {
                            assertThat(rows).extracting(JspManualMigrations.Row::getLine, JspManualMigrations.Row::getKind,
                                            JspManualMigrations.Row::getStatus, JspManualMigrations.Row::getCategories)
                                    .containsExactly(
                                            tuple(2, "declaration", "MANUAL", "DECLARATION"),
                                            // The orders loop is fully migratable: mirror, c:set, c:forEach, ${o.total}.
                                            tuple(3, "scriptlet", "MIGRATABLE", ""),
                                            tuple(4, "scriptlet", "MIGRATABLE", ""),
                                            tuple(5, "expression", "MIGRATABLE", ""),
                                            tuple(6, "scriptlet", "MIGRATABLE", ""),
                                            tuple(7, "scriptlet", "MANUAL", "DATA_ACCESS, VARIABLE"),
                                            tuple(8, "scriptlet", "MANUAL", "CONTROL_FLOW"),
                                            tuple(8, "scriptlet", "MANUAL", "BLOCK_DELIMITER"),
                                            tuple(9, "scriptlet", "MANUAL", "REQUEST_SESSION_STATE"),
                                            tuple(10, "scriptlet", "MANUAL", "RESPONSE_CONTROL, CONTROL_FLOW"),
                                            tuple(11, "expression", "MANUAL", "STATIC_CALL"),
                                            tuple(12, "scriptlet", "MANUAL", "OUTPUT_WRITING"),
                                            tuple(13, "tag attribute", "MANUAL", "TAG_ATTRIBUTE"));
                            assertThat(rows).filteredOn(r -> r.getLine() == 7).singleElement()
                                    .extracting(JspManualMigrations.Row::getReasons)
                                    .isEqualTo("set: value 'dataSource.getConnection()': 'dataSource' is a Java " +
                                               "variable EL can't see (not mirrored into a page attribute)");
                            assertThat(rows).filteredOn(r -> r.getLine() == 8)
                                    .extracting(JspManualMigrations.Row::getReasons)
                                    .containsExactly("if: condition 'user.isAdmin()': 'user' is a Java variable EL " +
                                                     "can't see (not mirrored into a page attribute)",
                                            "it closes or continues a block that stays Java");
                            assertThat(rows).filteredOn(r -> r.getLine() == 11).singleElement()
                                    .extracting(JspManualMigrations.Row::getReasons)
                                    .isEqualTo("expression: 'DateUtils' is a class: static members can't be translated");
                            assertThat(rows).filteredOn(r -> r.getLine() == 13).singleElement()
                                    .extracting(JspManualMigrations.Row::getReasons)
                                    .isEqualTo("<%= %> in attribute 'value' of <html:text>, which would be " +
                                               "${param.x} if the tag evaluates EL");
                        })
                        .dataTable(JspMigrationEffort.Row.class, rows ->
                                assertThat(rows).singleElement().satisfies(row -> {
                                    assertThat(row.getJavaConstructs()).isEqualTo(13);
                                    assertThat(row.getMigratable()).isEqualTo(4);
                                    assertThat(row.getManual()).isEqualTo(9);
                                    assertThat(row.getManualJavaLines()).isEqualTo(9);
                                })),
                // The markers themselves are checked in marksManualConstructs.
                jsp(PAGE, spec -> spec.after(actual -> actual))
        );
    }

    @Test
    void marksManualConstructs() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <% String n = request.getParameter("n"); %>
                        <% out.println(n); %>
                        """,
                        """
                        <p>intro</p>
                        ~~(Manual migration (VARIABLE): set: other Java code still uses 'n')~~><% String n = request.getParameter("n"); %>
                        ~~(Manual migration (OUTPUT_WRITING): it contains Java with no JSTL/EL equivalent)~~><% out.println(n); %>
                        """
                )
        );
    }

    @Test
    void fullyMigratablePageHasNoMarkers() {
        rewriteRun(
                spec -> spec.dataTable(JspMigrationEffort.Row.class, rows ->
                        assertThat(rows).singleElement().satisfies(row -> {
                            assertThat(row.getMigratable()).isEqualTo(3);
                            assertThat(row.getManual()).isZero();
                        })),
                jsp(
                        """
                        <p>intro</p>
                        <% if (request.getParameter("q") != null) { %>
                          <%= request.getParameter("q") %>
                        <% } %>
                        """
                )
        );
    }
}
