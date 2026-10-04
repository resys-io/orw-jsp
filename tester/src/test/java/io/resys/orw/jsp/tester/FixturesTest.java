package io.resys.orw.jsp.tester;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixturesTest {

    static final JspTester tester = JspTester.builder().webapp(Path.of("src/test/webapp")).build();

    @AfterAll
    static void stop() {
        tester.close();
    }

    private static Path fixture(Path dir) throws IOException {
        return Files.writeString(dir.resolve("typed.json"), """
                {
                  "page": "/typed.jsp",
                  "request": {
                    "person": { "@class": "io.resys.orw.jsp.tester.Person", "name": "Ann", "age": 42 },
                    "settings": { "theme": "dark" }
                  },
                  "session": { "tags": ["a", "b"] }
                }
                """);
    }

    @Test
    void typedValuesMapsAndLists() throws IOException {
        Fixtures.Fixture fixture = Fixtures.read(fixture(Files.createTempDirectory("fixture")));
        String out = tester.renderOk(fixture.request());
        assertThat(out).contains("<p>Ann (42)</p>", "<p>2 tags, first: a</p>", "<p>dark</p>");
    }

    @Test
    void missingExpectedOutputFailsUntilUpdated(@TempDir Path dir) throws IOException {
        Path fixture = fixture(dir);
        assertThatThrownBy(() -> Fixtures.verify(tester, fixture, false))
                .hasMessageContaining("no expected output typed.expected.html yet")
                .hasMessageContaining("-Dorw.tester.update=true");

        Fixtures.verify(tester, fixture, true);
        Path expected = dir.resolve("typed.expected.html");
        assertThat(expected).content().contains("<p>Ann (42)</p>");

        Fixtures.verify(tester, fixture, false); // now matches
    }

    @Test
    void comparisonIgnoresWhitespaceByDefault(@TempDir Path dir) throws IOException {
        Path fixture = fixture(dir);
        Fixtures.verify(tester, fixture, true);
        Path expected = dir.resolve("typed.expected.html");
        Files.writeString(expected, "\n\n" + Files.readString(expected).replace("\n", "\n    ") + "\n");
        Fixtures.verify(tester, fixture, false);

        Files.writeString(expected, Files.readString(expected).replace("Ann", "Bob"));
        assertThatThrownBy(() -> Fixtures.verify(tester, fixture, false))
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("the output differs from typed.expected.html");
    }

    @Test
    void exactComparison(@TempDir Path dir) throws IOException {
        Path fixture = fixture(dir);
        Files.writeString(fixture, Files.readString(fixture).replace("\"page\"", "\"compare\": \"EXACT\", \"page\""));
        Fixtures.verify(tester, fixture, true);
        Path expected = dir.resolve("typed.expected.html");
        Files.writeString(expected, Files.readString(expected) + " ");
        assertThatThrownBy(() -> Fixtures.verify(tester, fixture, false)).isInstanceOf(AssertionFailedError.class);
    }

    @Test
    void helpfulErrors(@TempDir Path dir) throws IOException {
        Path unknownClass = Files.writeString(dir.resolve("a.json"),
                "{\"page\": \"/typed.jsp\", \"request\": {\"p\": {\"@class\": \"com.nowhere.Missing\"}}}");
        assertThatThrownBy(() -> Fixtures.read(unknownClass)).hasMessageContaining("com.nowhere.Missing isn't on the test classpath");

        Path typo = Files.writeString(dir.resolve("b.json"),
                "{\"page\": \"/typed.jsp\", \"request\": {\"p\": {\"@class\": \"io.resys.orw.jsp.tester.Person\", \"nmae\": \"x\"}}}");
        assertThatThrownBy(() -> Fixtures.read(typo)).hasMessageContaining("nmae");
    }

    @Test
    void valueConversion() {
        Object date = Fixtures.value(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("@class", "java.util.Date").put("@value", "2024-01-31T12:00:00Z"), Path.of("x.json"));
        assertThat(date).isEqualTo(java.util.Date.from(java.time.Instant.parse("2024-01-31T12:00:00Z")));
    }

    private static Path helloFixture(Path dir, String mocks) throws IOException {
        return Files.writeString(dir.resolve("hello.json"), """
                {
                  "page": "/hello.jsp",
                  "request": { "name": "Ann", "price": 19.90 }%s
                }
                """.formatted(mocks));
    }

    @Test
    void updateAddsMissingMocks(@TempDir Path dir) throws IOException {
        Path fixture = helloFixture(dir, ",\n  \"mocks\": { \"acme:menu\": { \"mode\": \"EMPTY\" } }");
        Fixtures.verify(tester, fixture, true);
        // acme:menu keeps its configuration; acme:panel (also mocked, used by the page) is added.
        assertThat(fixture).content().isEqualTo("""
                {
                  "page": "/hello.jsp",
                  "request": {
                    "name": "Ann",
                    "price": 19.90
                  },
                  "mocks": {
                    "acme:menu": {
                      "mode": "EMPTY"
                    },
                    "acme:panel": {
                      "mode": "PLACEHOLDER"
                    }
                  }
                }
                """);
        // The added PLACEHOLDER entries render as before: the snapshot still matches.
        Fixtures.verify(tester, fixture, false);
    }

    @Test
    void updateAddsAMocksSectionAndLeavesCompleteFixturesAlone(@TempDir Path dir) throws IOException {
        Path fixture = helloFixture(dir, "");
        Fixtures.verify(tester, fixture, true);
        assertThat(Fixtures.read(fixture).request().getMocks()).containsOnlyKeys("acme:menu", "acme:panel");

        String complete = "{ \"page\": \"/hello.jsp\", \"request\": {\"name\": \"x\"}, " +
                          "\"mocks\": {\"acme:menu\": {}, \"acme:panel\": {\"mode\": \"BODY\"}} }";
        Files.writeString(fixture, complete);
        Fixtures.verify(tester, fixture, true);
        assertThat(fixture).content().isEqualTo(complete);
    }

    @Test
    void customMocksCantBeInFixtureFiles(@TempDir Path dir) throws IOException {
        Path fixture = helloFixture(dir, ",\n  \"mocks\": { \"acme:menu\": { \"mode\": \"CUSTOM\" } }");
        assertThatThrownBy(() -> Fixtures.read(fixture))
                .hasMessageContaining("CUSTOM mocks are Java, so they can only be defined in code");
    }
}
