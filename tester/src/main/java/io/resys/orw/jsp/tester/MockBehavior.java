package io.resys.orw.jsp.tester;

import java.util.Map;

/**
 * What a mocked tag renders, set per tag in a {@link RenderRequest} (or a fixture's {@code mocks}).
 *
 * @param mode      what to render.
 * @param text      for {@link Mode#TEXT}, the text to write in place of the tag.
 * @param variables page attributes the tag sets when it starts (so its body and the rest of the
 *                  page can use them), for tags that define variables.
 */
public record MockBehavior(Mode mode, String text, Map<String, Object> variables) {

    public enum Mode {
        /** Write the tag itself, with its attributes, around its body: makes mocks visible in output. */
        PLACEHOLDER,
        /** Write only the tag's body. */
        BODY,
        /** Write nothing, and skip the body. */
        EMPTY,
        /** Write {@link #text()} instead of the tag and its body. */
        TEXT
    }

    public MockBehavior {
        text = text == null ? "" : text;
        variables = variables == null ? Map.of() : Map.copyOf(variables);
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

    public MockBehavior withVariables(Map<String, Object> variables) {
        return new MockBehavior(mode, text, variables);
    }
}
