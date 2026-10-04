package io.resys.orw.jsp.tester;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JspTesterTest {

    static final JspTester tester = JspTester.builder()
            .webapp(Path.of("src/test/webapp"))
            .build();

    @AfterAll
    static void stop() {
        tester.close();
    }

    @Test
    void rendersWithParametersAndAttributes() {
        String out = tester.renderOk(RenderRequest.page("/hello.jsp")
                .param("q", "boots & socks")
                .requestAttribute("name", "Ann"));
        assertThat(out).contains("<h1>Hello Ann</h1>", "<p>boots & socks</p>");
        assertThat(tester.getMockedTaglibs()).containsExactly("http://acme.example/tags");
    }

    @Test
    void unknownTagsAreMockedAsPlaceholdersByDefault() {
        String out = tester.renderOk(RenderRequest.page("/hello.jsp").requestAttribute("name", "Ann"));
        assertThat(out).contains("<acme:panel title=\"T\">inside Ann</acme:panel>", "<acme:menu></acme:menu>",
                "<p>acme:upper(Ann)</p>");
    }

    @Test
    void mockBehaviors() {
        String out = tester.renderOk(RenderRequest.page("/hello.jsp")
                .requestAttribute("name", "Ann")
                .mock("acme:panel", MockBehavior.body())
                .mock("acme:menu", MockBehavior.text("<nav>menu</nav>")));
        assertThat(out).contains("\ninside Ann\n", "<nav>menu</nav>").doesNotContain("acme:panel");

        String empty = tester.renderOk(RenderRequest.page("/hello.jsp")
                .requestAttribute("name", "Ann")
                .mock("acme:panel", MockBehavior.empty().withVariables(Map.of("unused", 1))));
        assertThat(empty).doesNotContain("inside");
    }

    @Test
    void compilationErrorsAreReported() {
        Rendered rendered = tester.render(RenderRequest.page("/broken.jsp"));
        assertThat(rendered.status()).isEqualTo(500);
        assertThat(rendered.body()).contains("broken.jsp");
    }

    @Test
    void unresolvableTaglibsCanBeAnError() {
        try (JspTester strict = JspTester.builder().webapp(Path.of("src/test/webapp")).autoMock(false).build()) {
            assertThatThrownBy(() -> strict.render(RenderRequest.page("/hello.jsp")))
                    .hasMessageContaining("No TLD found for the tag libraries [http://acme.example/tags]");
        }
    }

    @Test
    void mocksDefinedInCode(@TempDir Path dir) throws IOException {
        try (JspTester coded = JspTester.builder().webapp(Path.of("src/test/webapp"))
                // A mock written in Java: gets the evaluated attributes and the rendered body.
                .mock("acme:panel", MockBehavior.custom(tag ->
                        "<section title=\"" + tag.attribute("title") + "\">" + tag.body().trim() + "</section>"))
                .mock("acme:menu", MockBehavior.text("<nav>shared menu</nav>"))
                .build()) {
            String out = coded.renderOk(RenderRequest.page("/hello.jsp").requestAttribute("name", "Ann"));
            assertThat(out).contains("<section title=\"T\">inside Ann</section>", "<nav>shared menu</nav>");

            // A request's (or fixture's) mock overrides the tester's.
            String overridden = coded.renderOk(RenderRequest.page("/hello.jsp").requestAttribute("name", "Ann")
                    .mock("acme:menu", MockBehavior.empty()));
            assertThat(overridden).contains("<section title=\"T\">").doesNotContain("shared menu");

            // Updating a fixture doesn't add entries for tags mocked in code: they'd override it.
            Path fixture = Files.writeString(dir.resolve("f.json"), "{\"page\": \"/hello.jsp\", \"request\": {\"name\": \"x\"}}");
            assertThat(coded.unconfiguredMocks(Fixtures.read(fixture).request())).isEmpty();
        }
    }

    @Test
    void customMockFailuresAreReported() {
        try (JspTester failing = JspTester.builder().webapp(Path.of("src/test/webapp"))
                .mock("acme:panel", MockBehavior.custom(tag -> { throw new IllegalStateException("boom"); }))
                .build()) {
            Rendered rendered = failing.render(RenderRequest.page("/hello.jsp").requestAttribute("name", "Ann"));
            assertThat(rendered.status()).isEqualTo(500);
            assertThat(rendered.body()).contains("The custom mock of <acme:panel> failed", "boom");
        }
    }
}
