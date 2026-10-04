package io.resys.orw.jsp.tester;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

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
}
