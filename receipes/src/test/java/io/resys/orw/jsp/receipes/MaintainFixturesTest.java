package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.receipes.table.JspFixtureChanges;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.List;

import static io.resys.orw.jsp.Assertions.jsp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.test.SourceSpecs.text;

class MaintainFixturesTest implements RewriteTest {

    private static final String ORDERS_PAGE = """
            <%@ page import="java.util.List, com.acme.User" %>
            <% List orders = (List) request.getAttribute("orders"); %>
            <% User user = (User) session.getAttribute("user"); %>
            <c:forEach var="order" items="${orders}">${order.customer.name} ${order.total}</c:forEach>
            ${param.q} ${user.name} ${title}
            <%= application.getAttribute(Constants.KEY) %>
            """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MaintainFixtures(null, null, null, null, null));
    }

    @Test
    void createsASkeletonForAPageWithoutFixtures() {
        rewriteRun(
                spec -> spec.dataTable(JspFixtureChanges.Row.class, rows ->
                        assertThat(rows).extracting(JspFixtureChanges.Row::getFixture, JspFixtureChanges.Row::getPage,
                                        JspFixtureChanges.Row::getAction)
                                .containsExactly(tuple("src/test/fixtures/orders.json", "/WEB-INF/views/orders.jsp",
                                        "CREATED"))),
                jsp(ORDERS_PAGE, spec -> spec.path("src/main/webapp/WEB-INF/views/orders.jsp")),
                text(null, """
                        {
                          "page": "/WEB-INF/views/orders.jsp",
                          "parameters": {
                            "q": ""
                          },
                          "request": {
                            "orders": [
                              {
                                "customer": {
                                  "name": ""
                                },
                                "total": ""
                              }
                            ],
                            "title": ""
                          },
                          "session": {
                            "user": {
                              "@class": "com.acme.User",
                              "name": ""
                            }
                          },
                          "_todo": [
                            "(non-literal key: Constants.KEY), read by application.getAttribute: set it by hand"
                          ]
                        }
                        """, spec -> spec.path("src/test/fixtures/orders.json"))
        );
    }

    @Test
    void addsWhatAnExistingFixtureLacksAndKeepsItsValues() {
        rewriteRun(
                spec -> spec.dataTable(JspFixtureChanges.Row.class, rows ->
                        assertThat(rows).extracting(JspFixtureChanges.Row::getAction, JspFixtureChanges.Row::getDetails)
                                .containsExactly(tuple("UPDATED", "added parameters.q, request.orders[].total, " +
                                                                  "request.title, session.user"))),
                jsp(ORDERS_PAGE, spec -> spec.path("src/main/webapp/WEB-INF/views/orders.jsp")),
                text(
                        """
                        {
                          "page": "/WEB-INF/views/orders.jsp",
                          "request": { "orders": [ { "customer": { "name": "Ann", "vip": true } }, { "customer": { "name": "Bob" } } ] }
                        }
                        """,
                        """
                        {
                          "page": "/WEB-INF/views/orders.jsp",
                          "request": {
                            "orders": [
                              {
                                "customer": {
                                  "name": "Ann",
                                  "vip": true
                                },
                                "total": null
                              },
                              {
                                "customer": {
                                  "name": "Bob"
                                },
                                "total": null
                              }
                            ],
                            "title": null
                          },
                          "parameters": {
                            "q": null
                          },
                          "session": {
                            "user": null
                          }
                        }
                        """,
                        // noTrim: the recipe writes a final newline, which trimming would drop from the expectation.
                        spec -> spec.path("src/test/fixtures/no-orders-yet.json").noTrim())
        );
    }

    @Test
    void completeFixturesAreLeftAsTheyAre() {
        rewriteRun(
                jsp("<p>${title}</p>", spec -> spec.path("src/main/webapp/home.jsp")),
                text("{ \"page\": \"/home.jsp\", \"request\": { \"title\": \"Welcome\" }, \"compare\": \"HTML\" }",
                        spec -> spec.path("src/test/fixtures/home.json"))
        );
    }

    @Test
    void orphansAreDeletedOnlyWhenAsked() {
        rewriteRun(
                spec -> spec.recipe(new MaintainFixtures(null, null, null, null, true)),
                jsp("<p>home</p>", spec -> spec.path("src/main/webapp/home.jsp")),
                text("{ \"page\": \"/home.jsp\" }", spec -> spec.path("src/test/fixtures/home.json")),
                text("{ \"page\": \"/WEB-INF/views/gone.jsp\" }", null, spec -> spec.path("src/test/fixtures/gone.json")),
                text("<p>gone</p>", null, spec -> spec.path("src/test/fixtures/gone.expected.html"))
        );
        rewriteRun(
                jsp("<p>home</p>", spec -> spec.path("src/main/webapp/home.jsp")),
                text("{ \"page\": \"/home.jsp\" }", spec -> spec.path("src/test/fixtures/home.json")),
                text("{ \"page\": \"/WEB-INF/views/gone.jsp\" }", spec -> spec.path("src/test/fixtures/gone.json"))
        );
    }

    @Test
    void includesAndExcludesChooseThePages() {
        rewriteRun(
                spec -> spec.recipe(new MaintainFixtures(null, null, List.of("/WEB-INF/views/**"),
                        List.of("/WEB-INF/views/admin/**", "**/*.jspf"), true)),
                jsp("<p>${a}</p>", spec -> spec.path("src/main/webapp/WEB-INF/views/a.jsp")),
                jsp("<p>${b}</p>", spec -> spec.path("src/main/webapp/WEB-INF/views/admin/b.jsp")),
                jsp("<p>${f}</p>", spec -> spec.path("src/main/webapp/WEB-INF/views/part.jspf")),
                jsp("<p>${o}</p>", spec -> spec.path("src/main/webapp/outside.jsp")),
                text(null, """
                        {
                          "page": "/WEB-INF/views/a.jsp",
                          "request": {
                            "a": ""
                          }
                        }
                        """, spec -> spec.path("src/test/fixtures/a.json")),
                // An excluded page's fixture isn't deleted, even if its page is gone.
                text("{ \"page\": \"/WEB-INF/views/admin/old.jsp\" }", spec -> spec.path("src/test/fixtures/old.json"))
        );
    }

    @Test
    void customDirectoriesAndNameCollisions() {
        rewriteRun(
                spec -> spec.recipe(new MaintainFixtures("test-fixtures/", "web", null, null, null)),
                jsp("<p>x</p>", spec -> spec.path("web/WEB-INF/views/home.jsp")),
                jsp("<p>y</p>", spec -> spec.path("web/home.jsp")),
                text(null, "{\n  \"page\": \"/WEB-INF/views/home.jsp\"\n}\n", spec -> spec.path("test-fixtures/home.json")),
                text(null, "{\n  \"page\": \"/home.jsp\"\n}\n", spec -> spec.path("test-fixtures/home-2.json"))
        );
    }

    @Test
    void invalidFixturesAreReportedAndLeftAlone() {
        rewriteRun(
                spec -> spec.dataTable(JspFixtureChanges.Row.class, rows ->
                        assertThat(rows).extracting(JspFixtureChanges.Row::getAction).containsExactly("SKIPPED")),
                jsp("<p>home</p>", spec -> spec.path("src/main/webapp/home.jsp")),
                text("{ \"page\": \"/home.jsp\" }", spec -> spec.path("src/test/fixtures/home.json")),
                text("not json", spec -> spec.path("src/test/fixtures/broken.json"))
        );
    }
}
