package io.resys.orw.jsp.tester;

import java.util.Map;
import java.util.function.Function;

/**
 * What a mocked tag renders, set per tag on the {@link JspTester.Builder#mock tester}, on a
 * {@link RenderRequest#mock request}, or in a fixture's {@code mocks}.
 *
 * @param mode      what to render.
 * @param text      for {@link Mode#TEXT}, the text to write in place of the tag.
 * @param variables page attributes the tag sets when it starts (so its body and the rest of the
 *                  page can use them), for tags that define variables.
 * @param renderer  for {@link Mode#CUSTOM}, what writes the tag's output.
 */
public record MockBehavior(Mode mode, String text, Map<String, Object> variables,
                           Function<MockInvocation, String> renderer) {

    public enum Mode {
        /** Write the tag itself, with its attributes, around its body: makes mocks visible in output. */
        PLACEHOLDER,
        /** Write only the tag's body. */
        BODY,
        /** Write nothing, and skip the body. */
        EMPTY,
        /** Write {@link #text()} instead of the tag and its body. */
        TEXT,
        /** Write what {@link #renderer()} returns for the tag (only in code, not in fixture files). */
        CUSTOM
    }

    public MockBehavior {
        text = text == null ? "" : text;
        variables = variables == null ? Map.of() : Map.copyOf(variables);
        if (mode == Mode.CUSTOM && renderer == null) {
            throw new IllegalArgumentException("A CUSTOM mock needs a renderer; see MockBehavior.custom(...)");
        }
    }

    public MockBehavior(Mode mode, String text, Map<String, Object> variables) {
        this(mode, text, variables, null);
    }

    public static MockBehavior placeholder() {
        return new MockBehavior(Mode.PLACEHOLDER, "", Map.of());
    }

    public static MockBehavior body() {
        return new MockBehavior(Mode.BODY, "", Map.of());
    }

    public static MockBehavior empty() {
        return new MockBehavior(Mode.EMPTY, "", Map.of());
    }

    public static MockBehavior text(String text) {
        return new MockBehavior(Mode.TEXT, text, Map.of());
    }

    /**
     * A mock written in Java: the renderer gets each use of the tag - its name, evaluated
     * attributes, rendered body, and the page context - and returns what to write instead.
     * <pre>{@code
     * MockBehavior.custom(tag -> messages.getProperty(tag.attribute("key")))
     * }</pre>
     */
    public static MockBehavior custom(Function<MockInvocation, String> renderer) {
        return new MockBehavior(Mode.CUSTOM, "", Map.of(), renderer);
    }

    public MockBehavior withVariables(Map<String, Object> variables) {
        return new MockBehavior(mode, text, variables, renderer);
    }
}
