package io.resys.orw.jsp.tester;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThymeleafTesterTest {

    static final JspTester jsp = JspTester.builder().webapp(Path.of("src/test/webapp")).build();
    static final ThymeleafTester thymeleaf = ThymeleafTester.builder().messages("messages").build();

    @AfterAll
    static void stop() {
        jsp.close();
    }

    @Test
    void rendersAsSpringMvcWouldFromTheSameRequest() {
        String out = thymeleaf.renderOk(RenderRequest.page("/web.jsp")
                .param("q", "boots & socks").param("page", "2")
                .applicationAttribute("version", "1.0")
                .sessionAttribute("user", Map.of("name", "Clerk"))
                .locale(Locale.forLanguageTag("fi")));
        assertThat(out).contains(
                "<p class=\"greeting\">Hei</p>",                  // #{...} from the MessageSource, by locale
                "<p class=\"q\">boots &amp; socks</p>",           // ${param.x}, escaped by th:text
                "<a href=\"/orders?page=2\">orders</a>",          // @{...}
                "<p class=\"version\">1.0</p>",                   // ${application.x}
                "<p class=\"lang\">fi</p>",
                "<p class=\"user\">Clerk</p>");                   // ${session.x}

        // Application attributes don't leak into the next render.
        assertThat(thymeleaf.renderOk(RenderRequest.page("/web.jsp"))).contains("<p class=\"version\"></p>")
                .doesNotContain("class=\"user\"");
    }

    @Test
    void templateNames() {
        assertThat(ThymeleafTester.defaultTemplateName("/WEB-INF/views/orders/list.jsp")).isEqualTo("orders/list");
        assertThat(ThymeleafTester.defaultTemplateName("/WEB-INF/jsp/a.jspf")).isEqualTo("a");
        assertThat(ThymeleafTester.defaultTemplateName("/index.jsp")).isEqualTo("index");
        assertThat(thymeleaf.templateFor(RenderRequest.page("/x.jsp").template("y"))).isEqualTo("y");
    }

    private static Path fixture(Path dir, String template) throws IOException {
        return Files.writeString(dir.resolve("typed.json"), """
                {
                  "page": "/typed.jsp",%s
                  "request": {
                    "person": { "@class": "io.resys.orw.jsp.tester.Person", "name": "Ann", "age": 42 },
                    "settings": { "theme": "dark" }
                  },
                  "session": { "tags": ["a", "b"] }
                }
                """.formatted(template == null ? "" : "\n  \"template\": \"" + template + "\","));
    }

    @Test
    void migratedTemplateMatchesThePagesExpectedOutput(@TempDir Path dir) throws IOException {
        Path fixture = fixture(dir, null);
        Fixtures.verify(jsp, fixture, true);           // the JSP page writes the reference output
        String reference = Files.readString(dir.resolve("typed.expected.html"));

        Fixtures.verify(thymeleaf, fixture, true);     // update mode: the template still only compares
        assertThat(dir.resolve("typed.expected.html")).content().isEqualTo(reference);
        thymeleaf.verify(fixture);
    }

    @Test
    void differencesAreShownAsHtmlTrees(@TempDir Path dir) throws IOException {
        Fixtures.verify(jsp, fixture(dir, null), true);
        Path broken = fixture(dir, "typed-broken");
        assertThatThrownBy(() -> thymeleaf.verify(broken))
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("The migrated template doesn't render what the original page did")
                .satisfies(e -> {
                    AssertionFailedError failure = (AssertionFailedError) e;
                    assertThat(failure.getExpected().getStringRepresentation()).contains("  <p>\n", "    Ann (42)\n");
                    assertThat(failure.getActual().getStringRepresentation()).contains("    Ann [42]\n");
                });
    }

    @Test
    void htmlComparisonIgnoresFormNotContent() {
        Comparison html = Comparison.HTML;
        assertThat(html.normalize("<input type=\"text\" name='q' value=\"\"/>\n  <br>"))
                .isEqualTo(html.normalize("<input value=\"\" name=\"q\" type=\"text\"><!-- c --><br/>"));
        assertThat(html.normalize("<p>a b</p>")).isNotEqualTo(html.normalize("<p>a  c</p>"));
        assertThat(html.normalize("<p class=\"x\">a</p>")).isNotEqualTo(html.normalize("<p class=\"y\">a</p>"));
    }

    @Test
    void templateErrorsAreReported() {
        Rendered rendered = thymeleaf.render(RenderRequest.page("/missing.jsp"));
        assertThat(rendered.status()).isEqualTo(500);
        assertThat(rendered.body()).contains("missing");
    }
}
