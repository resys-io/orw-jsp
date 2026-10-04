package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.receipes.table.JspVariableMigrations;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static io.resys.openrewrite.jsp.Assertions.jsp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.xml.Assertions.xml;

/**
 * RewriteTest runs each recipe a second time and fails if that changes anything again, so every
 * test here also checks that the migration recognizes its own output.
 */
class MigrateJavaVariablesToPageAttributesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateJavaVariablesToPageAttributes(null, null));
    }

    @Test
    void declarationsAndLoopVariables() {
        rewriteRun(
                spec -> spec.dataTable(JspVariableMigrations.Row.class, rows ->
                        assertThat(rows).extracting(JspVariableMigrations.Row::getVariable,
                                        JspVariableMigrations.Row::getStatus, JspVariableMigrations.Row::getConvertedUses)
                                .containsExactly(tuple("name", "MIGRATED", 1), tuple("o", "MIGRATED", 2))),
                jsp(
                        """
                        <p>intro</p>
                        <% String name = user.getName(); %>
                        <p><%= name %></p>
                        <% for (Order o : orders) { %>
                          <td><%= o.getTotal() %></td><td><%= o.getCustomer().getAddress().getCity() %></td>
                        <% } %>
                        """,
                        """
                        <p>intro</p>
                        <% String name = user.getName(); pageContext.setAttribute("name", name); %>
                        <p>${name}</p>
                        <% for (Order o : orders) { pageContext.setAttribute("o", o); %>
                          <td>${o.total}</td><td>${o.customer.address.city}</td>
                        <% } %>
                        """
                )
        );
    }

    @Test
    void everyAssignmentKeepsThePageAttributeInSync() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <% int count = 0; %>
                        <% count += items.size(); %>
                        <% if (more) { count++; } %>
                        <% for (int i = 0; i < 3; i++) { %>row <%= i %><% } %>
                        Total: <%= count %>
                        """,
                        """
                        <p>intro</p>
                        <% int count = 0; pageContext.setAttribute("count", count); %>
                        <% count += items.size(); pageContext.setAttribute("count", count); %>
                        <% if (more) { count++; pageContext.setAttribute("count", count); } %>
                        <% for (int i = 0; i < 3; i++) { pageContext.setAttribute("i", i); %>row ${i}<% } %>
                        Total: ${count}
                        """
                )
        );
    }

    @Test
    void unsafeVariablesAreSkippedAndReported() {
        rewriteRun(
                spec -> spec.dataTable(JspVariableMigrations.Row.class, rows ->
                        assertThat(rows).extracting(JspVariableMigrations.Row::getVariable,
                                        JspVariableMigrations.Row::getStatus, JspVariableMigrations.Row::getReason)
                                .containsExactly(
                                        tuple("line", "SKIPPED", "it is written somewhere a statement can't follow " +
                                                                 "(inside an expression, a braceless if/else/loop body, or a case label)"),
                                        tuple("item", "SKIPPED", "the page already uses 'item' in EL or as a tag's " +
                                                                 "attribute name, which a page attribute of that name would shadow"),
                                        tuple("param", "SKIPPED", "'param' is an EL reserved word or implicit object"),
                                        tuple("tmp", "SKIPPED", "no <%= %> outputs it (mirrorAll migrates it anyway)"),
                                        tuple("active", "SKIPPED", "it is written somewhere a statement can't follow " +
                                                                   "(inside an expression, a braceless if/else/loop body, or a case label)"))),
                jsp(
                        """
                        <p>intro</p>
                        <% String line; while ((line = reader.readLine()) != null) { %><%= line %><% } %>
                        ${item.label}<% String item = "x"; %><%= item %>
                        <% String param = "p"; %><%= param %>
                        <% int tmp = 1; %>
                        <% boolean active = true; if (expired) active = false; %><%= active %>
                        """
                )
        );
    }

    @Test
    void isGettersAndOtherExpressionsStayJava() {
        rewriteRun(
                spec -> spec.recipe(new MigrateJavaVariablesToPageAttributes(null, null)),
                jsp(
                        """
                        <p>intro</p>
                        <% User u = current(); %>
                        <%= u.isActive() %> <%= u.getName().toUpperCase() %> <%= u.getName() %>
                        """,
                        """
                        <p>intro</p>
                        <% User u = current(); pageContext.setAttribute("u", u); %>
                        <%= u.isActive() %> <%= u.getName().toUpperCase() %> ${u.name}
                        """
                )
        );
    }

    @Test
    void tagAttributesOnlyWithOption() {
        rewriteRun(
                spec -> spec.recipe(new MigrateJavaVariablesToPageAttributes(true, null)),
                jsp(
                        """
                        <p>intro</p>
                        <% String n = form.getName(); %>
                        <html:text property="name" value="<%= n %>"/>
                        """,
                        """
                        <p>intro</p>
                        <% String n = form.getName(); pageContext.setAttribute("n", n); %>
                        <html:text property="name" value="${n}"/>
                        """
                )
        );
    }

    @Test
    void tagAttributesAreLeftAloneByDefault() {
        rewriteRun(
                jsp(
                        """
                        <p>intro</p>
                        <% String n = form.getName(); %>
                        <html:text property="name" value="<%= n %>"/>
                        """
                )
        );
    }

    @Test
    void mirrorAllMigratesVariablesNotOutputDirectly() {
        rewriteRun(
                spec -> spec.recipe(new MigrateJavaVariablesToPageAttributes(null, true)),
                jsp(
                        """
                        <p>intro</p>
                        <% int total = cart.getTotal(); %>
                        """,
                        """
                        <p>intro</p>
                        <% int total = cart.getTotal(); pageContext.setAttribute("total", total); %>
                        """
                )
        );
    }

    @Test
    void nothingChangesWhenWebXmlDisablesEl() {
        rewriteRun(
                spec -> spec.dataTable(JspVariableMigrations.Row.class, rows ->
                        assertThat(rows).singleElement().satisfies(row -> {
                            assertThat(row.getStatus()).isEqualTo("SKIPPED");
                            assertThat(row.getReason()).isEqualTo("WEB-INF/web.xml declares Servlet 2.3 or earlier " +
                                                                  "(DTD-based), in which EL is disabled by default");
                        })),
                xml("<web-app><display-name>legacy</display-name></web-app>", spec -> spec.path("WEB-INF/web.xml")),
                jsp(
                        """
                        <p>intro</p>
                        <% String name = user.getName(); %><%= name %>
                        """
                )
        );
    }

    @Test
    void servlet24WebXmlAllowsMigration() {
        rewriteRun(
                xml("<web-app version=\"2.4\"></web-app>", spec -> spec.path("WEB-INF/web.xml")),
                jsp(
                        """
                        <p>intro</p>
                        <% String name = user.getName(); %><%= name %>
                        """,
                        """
                        <p>intro</p>
                        <% String name = user.getName(); pageContext.setAttribute("name", name); %>${name}
                        """
                )
        );
    }

    @Test
    void nothingChangesOnPagesIgnoringEl() {
        rewriteRun(
                jsp(
                        """
                        <%@ page isELIgnored="true" %>
                        <% String name = user.getName(); %><%= name %>
                        """
                )
        );
    }
}
