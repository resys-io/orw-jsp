package io.resys.orw.jsp.receipes;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds tester fixtures (the JSON format of {@code io.resys.orw.jsp.tester.Fixtures}) for a page
 * from its {@link ModelAttributeCollector inputs}, and adds a page's missing inputs to existing
 * fixtures, for {@link MaintainFixtures}.
 */
final class FixtureSkeletons {

    static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true)
            .configure(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES, false);

    /** Fixtures as the tester writes them: {@code "key": value}, two-space indents, {@code []} for empties. */
    private static final ObjectWriter PRETTY = JSON.writer(new Printer(new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"))
            .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER))));

    private static final Pattern COLLECTION = Pattern.compile(
            "(?:java\\.util\\.)?(?:List|Collection|Set|ArrayList|LinkedList|HashSet|Iterable)(?:<(.*)>)?");
    private static final Pattern MAP = Pattern.compile("(?:java\\.util\\.)?(?:Map|HashMap|LinkedHashMap|TreeMap)(?:<.*>)?");
    private static final Pattern DYNA_FORM = Pattern.compile(".*\\bDyna\\w*Form");
    private static final Set<String> TEXT = Set.of("String", "java.lang.String", "CharSequence", "Object", "java.lang.Object");
    private static final Set<String> NUMBER = Set.of("int", "long", "short", "byte", "double", "float",
            "Integer", "Long", "Short", "Byte", "Double", "Float", "Number", "BigDecimal", "BigInteger",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double",
            "java.lang.Float", "java.lang.Number", "java.math.BigDecimal", "java.math.BigInteger");
    private static final Set<String> BOOLEAN = Set.of("boolean", "Boolean", "java.lang.Boolean");

    /** A skeleton fixture, and what couldn't go in it. */
    record Skeleton(ObjectNode json, List<String> todos) {
    }

    private FixtureSkeletons() {
    }

    // -----------------------------------------------------------------------------------------
    // Building
    // -----------------------------------------------------------------------------------------

    static Skeleton skeleton(String page, Collection<ModelAttributeCollector.Attribute> inputs) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode fixture = nodes.objectNode();
        fixture.put("page", page);
        ObjectNode parameters = nodes.objectNode();
        ObjectNode request = nodes.objectNode();
        ObjectNode session = nodes.objectNode();
        ObjectNode application = nodes.objectNode();
        List<String> todos = new ArrayList<>();
        for (ModelAttributeCollector.Attribute input : inputs) {
            if (input.name.startsWith("(")) {
                todos.add(input.name + ", read by " + String.join(", ", input.evidence) + ": set it by hand");
                continue;
            }
            if (input.type != null && DYNA_FORM.matcher(input.type).matches()) {
                todos.add("form bean '" + input.name + "' (" + input.type + ") is created by Struts: set its " +
                          "fields with parameters");
                continue;
            }
            switch (input.scope) {
                case PARAMETER -> parameters.set(input.name, "java.lang.String[]".equals(input.type) ?
                        nodes.arrayNode().add("") : nodes.textNode(""));
                case SESSION -> session.set(input.name, value(input, todos));
                case APPLICATION -> application.set(input.name, value(input, todos));
                case TILES -> todos.add("Tiles attribute '" + input.name + "': the tester has no Tiles context");
                default -> request.set(input.name, value(input, todos));
            }
        }
        for (Map.Entry<String, ObjectNode> section : List.of(Map.entry("parameters", parameters),
                Map.entry("request", request), Map.entry("session", session), Map.entry("application", application))) {
            if (!section.getValue().isEmpty()) {
                fixture.set(section.getKey(), section.getValue());
            }
        }
        if (!todos.isEmpty()) {
            ArrayNode todo = fixture.putArray("_todo");
            todos.forEach(todo::add);
        }
        return new Skeleton(fixture, todos);
    }

    /**
     * The shape of a value: its type, the properties read from it, and (for a collection) its
     * element's shape.
     */
    private static final class Shape {
        @Nullable String type;
        final Map<String, Shape> fields = new LinkedHashMap<>();
        @Nullable Shape element;

        Shape element() {
            if (element == null) {
                element = new Shape();
            }
            return element;
        }

        Shape field(String name) {
            return fields.computeIfAbsent(name, n -> new Shape());
        }
    }

    private static JsonNode value(ModelAttributeCollector.Attribute input, List<String> todos) {
        Shape root = new Shape();
        root.type = input.type;
        input.properties.forEach((path, type) -> {
            Shape shape = root;
            for (String segment : path.split("\\.")) {
                if (segment.equals("[]")) {
                    shape = shape.element();
                    continue;
                }
                boolean list = segment.endsWith("[]");
                shape = shape.field(list ? segment.substring(0, segment.length() - 2) : segment);
                if (list) {
                    shape = shape.element();
                }
            }
            if (!type.isEmpty()) {
                shape.type = type;
            }
        });
        return json(root, input.name, todos);
    }

    private static JsonNode json(Shape shape, String where, List<String> todos) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        String type = shape.type == null ? null : shape.type.replaceAll("\\s+", "");
        Matcher collection = type == null ? null : COLLECTION.matcher(type);
        boolean isCollection = shape.element != null || (collection != null && collection.matches()) ||
                               (type != null && type.endsWith("[]"));
        if (isCollection) {
            Shape element = shape.element == null ? new Shape() : shape.element;
            if (element.type == null && collection != null && collection.matches() && collection.group(1) != null) {
                element.type = collection.group(1);
            } else if (element.type == null && type != null && type.endsWith("[]")) {
                element.type = type.substring(0, type.length() - 2);
            }
            return nodes.arrayNode().add(json(element, where + "[]", todos));
        }
        boolean isMap = type != null && MAP.matcher(type).matches();
        if (!shape.fields.isEmpty() || (type != null && !isMap && !TEXT.contains(type) && !NUMBER.contains(type) &&
                                         !BOOLEAN.contains(type))) {
            if (type != null && ("java.util.Date".equals(type) || "Date".equals(type))) {
                return nodes.objectNode().put("@class", "java.util.Date").put("@value", "2024-01-01T00:00:00Z");
            }
            ObjectNode object = nodes.objectNode();
            if (type != null && !isMap) {
                if (type.contains(".")) {
                    object.put("@class", type.replaceAll("<.*", ""));
                } else {
                    todos.add(where + ": type " + type + " isn't fully qualified (no page import): add \"@class\"");
                }
            }
            shape.fields.forEach((name, field) -> object.set(name, json(field, where + "." + name, todos)));
            return object;
        }
        if (type != null && NUMBER.contains(type)) {
            return nodes.numberNode(0);
        }
        if (type != null && BOOLEAN.contains(type)) {
            return nodes.booleanNode(false);
        }
        return nodes.textNode("");
    }

    // -----------------------------------------------------------------------------------------
    // Updating
    // -----------------------------------------------------------------------------------------

    /**
     * Adds the inputs the skeleton has and the fixture doesn't - sections, inputs, nested
     * properties, also in each element of a list - as {@code null}, without changing anything the
     * fixture has.
     * <p>
     * {@code null}, not the skeleton's placeholder: a fixture without an input may lack it on
     * purpose (e.g. no signed-in user), and a null attribute (or property, or parameter) is the same
     * as an absent one, so the fixture still renders exactly as before. The key is there to fill in.
     *
     * @return the paths added, e.g. {@code request.orders[].customer.email}.
     */
    static List<String> addMissing(ObjectNode fixture, ObjectNode skeleton) {
        List<String> added = new ArrayList<>();
        for (String section : List.of("parameters", "request", "session", "application")) {
            if (skeleton.has(section)) {
                if (!fixture.has(section)) {
                    ObjectNode inputs = fixture.putObject(section);
                    skeleton.get(section).fieldNames().forEachRemaining(name -> {
                        inputs.putNull(name);
                        added.add(section + "." + name);
                    });
                } else if (fixture.get(section).isObject()) {
                    merge(fixture.get(section), skeleton.get(section), section, added);
                }
            }
        }
        // A property added to each element of a list is one addition.
        return new ArrayList<>(new LinkedHashSet<>(added));
    }

    private static void merge(JsonNode existing, JsonNode skeleton, String path, List<String> added) {
        if (existing.isObject() && skeleton.isObject()) {
            ObjectNode object = (ObjectNode) existing;
            for (Iterator<Map.Entry<String, JsonNode>> fields = skeleton.fields(); fields.hasNext(); ) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (field.getKey().startsWith("@")) {
                    continue;
                }
                if (!object.has(field.getKey())) {
                    object.putNull(field.getKey());
                    added.add(path + "." + field.getKey());
                } else {
                    merge(object.get(field.getKey()), field.getValue(), path + "." + field.getKey(), added);
                }
            }
        } else if (existing.isArray() && skeleton.isArray() && !skeleton.isEmpty()) {
            for (JsonNode element : existing) {
                merge(element, skeleton.get(0), path + "[]", added);
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Reading and writing
    // -----------------------------------------------------------------------------------------

    static @Nullable ObjectNode read(String text) {
        try {
            JsonNode node = JSON.readTree(text);
            return node instanceof ObjectNode ? (ObjectNode) node : null;
        } catch (IOException e) {
            return null;
        }
    }

    static String write(ObjectNode fixture) {
        try {
            return PRETTY.writeValueAsString(fixture) + "\n";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Jackson's pretty printer, with empty arrays and objects as {@code []} and <code>{}</code>. */
    private static final class Printer extends DefaultPrettyPrinter {
        Printer(DefaultPrettyPrinter base) {
            super(base);
        }

        @Override
        public Printer createInstance() {
            return new Printer(this);
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
}
