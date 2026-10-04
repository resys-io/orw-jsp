package io.resys.orw.jsp.tester;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Test fixtures: a JSON file with a {@link RenderRequest}'s inputs, and next to it the expected
 * output ({@code orders.json} goes with {@code orders.expected.html}).
 * <pre>{@code
 * {
 *   "page": "/WEB-INF/views/orders.jsp",
 *   "method": "GET",
 *   "locale": "fi-FI",
 *   "parameters": { "q": "boots", "ids": ["1", "2"] },
 *   "request": {
 *     "orders": [ { "@class": "com.acme.Order", "id": 1, "total": 9.5 } ],
 *     "title": "Orders"
 *   },
 *   "session": { "user": { "@class": "com.acme.User", "name": "Ann" } },
 *   "application": {},
 *   "mocks": { "acme:menu": { "mode": "TEXT", "text": "<nav/>" } },
 *   "compare": "WHITESPACE"
 * }
 * }</pre>
 * Attribute values: an object with {@code "@class"} becomes an instance of that class (its other
 * properties set on its fields), so scriptlets can cast it; {@code "@value"} gives a value to convert
 * instead, e.g. <code>{"@class": "java.util.Date", "@value": "2024-01-31T12:00:00Z"}</code>. Other
 * objects become {@code Map}s and arrays {@code List}s, which EL and JSTL handle as beans and
 * collections. Classes are loaded from the test classpath.
 * <p>
 * Mocks are keyed by tag as written on the page; {@code mode} is {@code PLACEHOLDER} (the default),
 * {@code BODY}, {@code EMPTY}, or {@code TEXT} (with {@code text}); {@code variables} are page
 * attributes the tag sets. {@code compare} is {@code WHITESPACE} (the default) or {@code EXACT}.
 * <p>
 * Expected output is created and updated, rather than compared, when updating is on: the
 * {@value #UPDATE_PROPERTY} system property ({@code -Dorw.tester.update=true}), or
 * {@link JspTester.Builder#updateFixtures}. A fixture without expected output fails until then.
 */
public final class Fixtures {

    public static final String UPDATE_PROPERTY = "orw.tester.update";

    private static final ObjectMapper JSON = new ObjectMapper()
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
            // 19.90 stays 19.90 (a BigDecimal, trailing zero kept), as money in fixtures should.
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false)
            .findAndRegisterModules();

    /** Writes fixtures as people do: {@code "key": value}, two-space indents, one array element per line. */
    private static final ObjectWriter PRETTY = JSON.writer(new FixturePrinter(new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"))
            .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))));

    /** Jackson's pretty printer, but with empty arrays and objects as {@code []} and <code>{}</code>, not {@code [ ]}. */
    private static final class FixturePrinter extends DefaultPrettyPrinter {
        FixturePrinter(DefaultPrettyPrinter base) {
            super(base);
        }

        @Override
        public FixturePrinter createInstance() {
            return new FixturePrinter(this);
        }

        @Override
        public void writeEndArray(JsonGenerator generator, int values) throws IOException {
            if (values == 0) {
                if (!_arrayIndenter.isInline()) {
                    --_nesting;
                }
                generator.writeRaw(']');
            } else {
                super.writeEndArray(generator, values);
            }
        }

        @Override
        public void writeEndObject(JsonGenerator generator, int entries) throws IOException {
            if (entries == 0) {
                if (!_objectIndenter.isInline()) {
                    --_nesting;
                }
                generator.writeRaw('}');
            } else {
                super.writeEndObject(generator, entries);
            }
        }
    }

    /** A fixture file: the request it describes, and how to compare. */
    public record Fixture(RenderRequest request, Comparison comparison) {
    }

    private Fixtures() {
    }

    static boolean updateRequested() {
        return Boolean.parseBoolean(System.getProperty(UPDATE_PROPERTY, "false"));
    }

    public static Path expectedOutput(Path fixture) {
        String name = fixture.getFileName().toString();
        String base = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
        return fixture.resolveSibling(base + ".expected.html");
    }

    /**
     * Renders the fixture and compares the output with the expected output, or (when updating)
     * writes the output as the expected output.
     */
    public static void verify(Renderer renderer, Path fixturePath, boolean update) {
        Fixture fixture = read(fixturePath);
        Rendered rendered = renderer.render(fixture.request());
        if (!rendered.ok()) {
            throw new AssertionError(fixturePath + ": rendering " + fixture.request().getPage() + " failed with status " +
                                     rendered.status() + ":\n" + rendered.body());
        }
        Path expectedPath = expectedOutput(fixturePath);
        try {
            if (update) {
                Files.writeString(expectedPath, rendered.body(), StandardCharsets.UTF_8);
                addMocks(fixturePath, renderer.unconfiguredMocks(fixture.request()));
                return;
            }
            if (!Files.exists(expectedPath)) {
                throw new AssertionError(fixturePath + ": no expected output " + expectedPath.getFileName() +
                                         " yet. Run with -D" + UPDATE_PROPERTY + "=true to create it from the current " +
                                         "output, and review it.");
            }
            String expected = Files.readString(expectedPath, StandardCharsets.UTF_8);
            Comparison comparison = fixture.comparison();
            if (!comparison.normalize(expected).equals(comparison.normalize(rendered.body()))) {
                throw new AssertionFailedError(fixturePath + ": the output differs from " + expectedPath.getFileName() +
                                               " (" + comparison + " comparison). Run with -D" + UPDATE_PROPERTY +
                                               "=true to accept it.", expected, rendered.body());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A dynamic test for each fixture ({@code *.json}) under a directory, by path.
     */
    public static Stream<DynamicTest> dynamicTests(Renderer renderer, Path directory, boolean update) {
        List<Path> fixtures;
        try (Stream<Path> files = Files.walk(directory)) {
            fixtures = files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return fixtures.stream().map(fixture -> DynamicTest.dynamicTest(directory.relativize(fixture).toString(),
                fixture.toUri(), () -> verify(renderer, fixture, update)));
    }

    /**
     * Adds a {@code PLACEHOLDER} entry to the fixture's {@code mocks} for each tag given (mocked
     * tags its page uses that nothing configured), so they're there to edit. Leaves existing entries
     * alone, and the file untouched if there's nothing to add.
     */
    private static void addMocks(Path fixturePath, Set<String> tags) throws IOException {
        if (tags.isEmpty()) {
            return;
        }
        ObjectNode root = (ObjectNode) JSON.readTree(fixturePath.toFile());
        ObjectNode mocks = root.has("mocks") && root.get("mocks").isObject() ?
                (ObjectNode) root.get("mocks") : root.putObject("mocks");
        for (String tag : tags) {
            if (!mocks.has(tag)) {
                mocks.putObject(tag).put("mode", MockBehavior.Mode.PLACEHOLDER.name());
            }
        }
        Files.writeString(fixturePath, PRETTY.writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------------------------

    public static Fixture read(Path fixture) {
        JsonNode root;
        try {
            root = JSON.readTree(fixture.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(fixture + ": " + e.getMessage(), e);
        }
        String page = text(root, "page");
        if (page == null) {
            throw new IllegalArgumentException(fixture + ": \"page\" is required");
        }
        RenderRequest request = RenderRequest.page(page);
        if (text(root, "method") != null) {
            request.method(text(root, "method"));
        }
        if (text(root, "locale") != null) {
            request.locale(Locale.forLanguageTag(text(root, "locale")));
        }
        fields(root, "parameters").forEach((name, value) -> {
            if (value.isArray()) {
                value.forEach(v -> request.param(name, v.asText()));
            } else {
                request.param(name, value.asText());
            }
        });
        fields(root, "request").forEach((name, value) -> request.requestAttribute(name, value(value, fixture)));
        fields(root, "session").forEach((name, value) -> request.sessionAttribute(name, value(value, fixture)));
        fields(root, "application").forEach((name, value) -> request.applicationAttribute(name, value(value, fixture)));
        fields(root, "mocks").forEach((tag, value) -> {
            MockBehavior.Mode mode = value.hasNonNull("mode") ?
                    MockBehavior.Mode.valueOf(value.get("mode").asText().toUpperCase(Locale.ROOT)) :
                    MockBehavior.Mode.PLACEHOLDER;
            if (mode == MockBehavior.Mode.CUSTOM) {
                throw new IllegalArgumentException(fixture + ": mock '" + tag + "': CUSTOM mocks are Java, so they " +
                                                   "can only be defined in code, e.g. JspTester.builder().mock(\"" +
                                                   tag + "\", MockBehavior.custom(...))");
            }
            Map<String, Object> variables = new LinkedHashMap<>();
            fields(value, "variables").forEach((name, v) -> variables.put(name, value(v, fixture)));
            request.mock(tag, new MockBehavior(mode, text(value, "text"), variables));
        });
        Comparison comparison = text(root, "compare") == null ? Comparison.WHITESPACE :
                Comparison.valueOf(text(root, "compare").toUpperCase(Locale.ROOT));
        return new Fixture(request, comparison);
    }

    /**
     * Converts a JSON value to the Java value a page gets.
     */
    static Object value(JsonNode node, Path fixture) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            if (node.hasNonNull("@class")) {
                String className = node.get("@class").asText();
                try {
                    Class<?> type = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
                    JsonNode content = node.has("@value") ? node.get("@value") : withoutClass((ObjectNode) node);
                    return JSON.convertValue(content, type);
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException(fixture + ": class " + className + " isn't on the test classpath", e);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(fixture + ": can't make a " + className + " of " + node + ": " +
                                                       e.getMessage(), e);
                }
            }
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(field -> map.put(field.getKey(), value(field.getValue(), fixture)));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(element -> list.add(value(element, fixture)));
            return list;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.canConvertToInt() ? (Object) node.asInt() : (Object) node.asLong();
        }
        return node.decimalValue();
    }

    /**
     * The object without its {@code @class} keys (also nested ones: Jackson creates nested objects
     * from the declared field types).
     */
    private static JsonNode withoutClass(ObjectNode node) {
        ObjectNode copy = node.deepCopy();
        strip(copy);
        return copy;
    }

    private static void strip(JsonNode node) {
        if (node instanceof ObjectNode) {
            ((ObjectNode) node).remove("@class");
        }
        for (Iterator<JsonNode> children = node.elements(); children.hasNext(); ) {
            strip(children.next());
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Map<String, JsonNode> fields(JsonNode node, String field) {
        Map<String, JsonNode> fields = new LinkedHashMap<>();
        if (node.hasNonNull(field)) {
            node.get(field).fields().forEachRemaining(e -> fields.put(e.getKey(), e.getValue()));
        }
        return fields;
    }
}
