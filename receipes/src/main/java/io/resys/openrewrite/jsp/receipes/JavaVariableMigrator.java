package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.tree.Jsp;
import org.jspecify.annotations.Nullable;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.openrewrite.Tree.randomId;

/**
 * Mirrors a page's Java (scriptlet) variables into page-scoped attributes and turns the
 * expressions that only output them into EL, for {@link MigrateJavaVariablesToPageAttributes}.
 * <p>
 * Works on one page's own content; statically included files are read-only through the page and
 * get migrated when the recipe runs on them. See the recipe for the rules.
 */
final class JavaVariableMigrator {

    enum Status {
        MIGRATED, SKIPPED
    }

    record Result(String name, Status status, String reason, int sites, int convertedUses) {
    }

    private static final Set<String> EL_UNUSABLE = Set.of(
            // Reserved words
            "and", "or", "not", "eq", "ne", "lt", "gt", "le", "ge", "true", "false", "null", "instanceof",
            "empty", "div", "mod",
            // Implicit objects
            "pageContext", "pageScope", "requestScope", "sessionScope", "applicationScope", "param",
            "paramValues", "header", "headerValues", "cookie", "initParam");

    private static final Pattern GETTER_CHAIN_ONLY = Pattern.compile(
            "\\s*([A-Za-z_$][\\w$]*)((?:\\s*\\.\\s*get[A-Z][\\w$]*\\s*\\(\\s*\\))*)\\s*");

    private static final Pattern GETTER = Pattern.compile("\\.\\s*get([A-Z][\\w$]*)\\s*\\(\\s*\\)");

    private static final Pattern WHOLE_EXPRESSION = Pattern.compile("<%=(.*?)%>", Pattern.DOTALL);

    private final boolean convertTagAttributes;
    private final boolean mirrorAll;
    /** Variables some {@code <%= %>} only outputs (alone or through a getter chain). */
    private final Set<String> outputNames = new LinkedHashSet<>();

    /** Per scriptlet: its recognized sites, by node id. */
    private final Map<UUID, List<JavaStatements.Site>> sitesByScriptlet = new LinkedHashMap<>();
    /** Per name: why it can't be migrated, if it can't. */
    private final Map<String, String> blocked = new LinkedHashMap<>();
    private final Map<String, Integer> siteCounts = new LinkedHashMap<>();
    private final Map<String, Integer> convertedUses = new HashMap<>();
    private final Set<String> elNames = new LinkedHashSet<>();
    private final Set<String> alreadyMirrored = new LinkedHashSet<>();
    private final Set<String> fields = new LinkedHashSet<>();

    JavaVariableMigrator(boolean convertTagAttributes, boolean mirrorAll) {
        this.convertTagAttributes = convertTagAttributes;
        this.mirrorAll = mirrorAll;
    }

    /**
     * @return {@code true} if the page turns EL off with {@code <%@ page isELIgnored="true" %>}.
     */
    static boolean elIgnored(Jsp.Document document) {
        for (Jsp.Content node : document.getNodes()) {
            if (node instanceof Jsp.Directive && "page".equals(((Jsp.Directive) node).getName())) {
                for (Jsp.Attribute attribute : ((Jsp.Directive) node).getAttributes()) {
                    if ("isELIgnored".equals(attribute.getName()) &&
                        "true".equalsIgnoreCase(attribute.getValue().getValue().trim())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Analyzes the page without changing it: which variables it declares, and which of them can be
     * migrated.
     */
    List<Result> analyze(Jsp.Document document) {
        collect(document.getNodes());

        // Every write to a candidate must be one a statement can follow.
        for (String name : siteCounts.keySet()) {
            Set<Integer> known = new LinkedHashSet<>();
            List<int[]> headers = new ArrayList<>();
            List<Integer> unknown = new ArrayList<>();
            for (Map.Entry<UUID, List<JavaStatements.Site>> entry : sitesByScriptlet.entrySet()) {
                for (JavaStatements.Site site : entry.getValue()) {
                    if (site.name().equals(name)) {
                        known.add(chunkKey(entry.getKey(), site.namePosition()));
                        if (site.headerEnd() >= 0) {
                            headers.add(new int[]{chunkKey(entry.getKey(), site.namePosition()),
                                    chunkKey(entry.getKey(), site.headerEnd())});
                        }
                        if (site.insertAt() < 0 && site.kind() == JavaStatements.Kind.LOOP_VARIABLE) {
                            block(name, "a loop declaring it has no braced body to set it in");
                        }
                    }
                }
            }
            for (Map.Entry<UUID, String> chunk : javaChunks.entrySet()) {
                for (int position : JavaStatements.allWrites(chunk.getValue(), name)) {
                    int key = chunkKey(chunk.getKey(), position);
                    boolean inHeader = headers.stream().anyMatch(h -> key > h[0] && key < h[1]);
                    if (!known.contains(key) && !inHeader) {
                        unknown.add(position);
                    }
                }
            }
            if (!unknown.isEmpty()) {
                block(name, "it is written somewhere a statement can't follow (inside an expression, " +
                            "a braceless if/else/loop body, or a case label)");
            }
            if (EL_UNUSABLE.contains(name)) {
                block(name, "'" + name + "' is an EL reserved word or implicit object");
            }
            if (fields.contains(name)) {
                block(name, "it is a field declared in <%! %>, shared by all requests");
            }
            if (!mirrorAll && !outputNames.contains(name) && !alreadyMirrored.contains(name)) {
                block(name, "no <%= %> outputs it (mirrorAll migrates it anyway)");
            }
            if (elNames.contains(name) && !alreadyMirrored.contains(name)) {
                block(name, "the page already uses '" + name + "' in EL or as a tag's attribute name, which a " +
                            "page attribute of that name would shadow");
            }
        }

        List<Result> results = new ArrayList<>();
        for (String name : siteCounts.keySet()) {
            String reason = blocked.get(name);
            results.add(new Result(name, reason == null ? Status.MIGRATED : Status.SKIPPED,
                    reason == null ? "" : reason, siteCounts.get(name), 0));
        }
        return results;
    }

    private final Map<UUID, String> javaChunks = new LinkedHashMap<>();
    private final Map<UUID, Integer> chunkIndex = new HashMap<>();

    private int chunkKey(UUID chunk, int position) {
        return chunkIndex.get(chunk) * 1_000_000 + position;
    }

    private void collect(List<Jsp.Content> nodes) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Scriptlet) {
                String code = ((Jsp.Scriptlet) node).getCodeSource();
                addChunk(node.getId(), code);
                List<JavaStatements.Site> sites = JavaStatements.sites(code);
                sitesByScriptlet.put(node.getId(), sites);
                for (JavaStatements.Site site : sites) {
                    if (site.kind() != JavaStatements.Kind.ASSIGNMENT) {
                        siteCounts.merge(site.name(), 1, Integer::sum);
                    }
                }
                Matcher mirrored = Pattern.compile(
                        "pageContext\\s*\\.\\s*setAttribute\\s*\\(\\s*\"([A-Za-z_$][\\w$]*)\"\\s*,\\s*([A-Za-z_$][\\w$]*)\\s*\\)")
                        .matcher(code);
                while (mirrored.find()) {
                    if (mirrored.group(1).equals(mirrored.group(2))) {
                        alreadyMirrored.add(mirrored.group(1));
                    }
                }
            } else if (node instanceof Jsp.Declaration) {
                String code = ((Jsp.Declaration) node).getCodeSource();
                addChunk(node.getId(), code);
                for (JspVariables.Definition definition : JspVariables.javaDeclarations(code, true, 0)) {
                    fields.add(definition.name());
                }
            } else if (node instanceof Jsp.ExpressionScriptlet) {
                String code = ((Jsp.ExpressionScriptlet) node).getCodeSource();
                addChunk(node.getId(), code);
                addOutputName(code);
            } else if (node instanceof Jsp.ExpressionLanguage) {
                elNames.addAll(JspVariables.elUses(((Jsp.ExpressionLanguage) node).getExpression()));
            } else if (node instanceof Jsp.Tag) {
                Jsp.Tag tag = (Jsp.Tag) node;
                for (Jsp.Attribute attribute : tag.getAttributes()) {
                    String value = attribute.getValue().getValue();
                    for (String el : JspVariables.elInText(value)) {
                        elNames.addAll(JspVariables.elUses(el));
                    }
                    int i = 0;
                    for (String code : JspVariables.expressionsInText(value)) {
                        addChunk(UUID.nameUUIDFromBytes((attribute.getId() + ":" + i++).getBytes()), code);
                    }
                    Matcher whole = WHOLE_EXPRESSION.matcher(value);
                    if (convertTagAttributes && whole.matches()) {
                        addOutputName(whole.group(1));
                    }
                    // name="x" (Struts), var="x", id="x" (useBean, logic:iterate, bean:define):
                    // names in the attribute world.
                    if (Set.of("name", "var", "id", "varStatus", "collection").contains(attribute.getName()) &&
                        value.matches("[A-Za-z_$][\\w$]*")) {
                        elNames.add(value);
                    }
                }
                if (tag.getBody() != null) {
                    collect(tag.getBody());
                }
            }
        }
    }

    private void addOutputName(String code) {
        Matcher m = GETTER_CHAIN_ONLY.matcher(code);
        if (m.matches()) {
            outputNames.add(m.group(1));
        }
    }

    private void addChunk(UUID id, String code) {
        chunkIndex.put(id, chunkIndex.size());
        javaChunks.put(id, code);
    }

    private void block(String name, String reason) {
        blocked.putIfAbsent(name, reason);
    }

    /**
     * Applies the migration of every variable {@link #analyze} found migratable.
     *
     * @return the changed document (or the same one), and the analysis results with use counts.
     */
    Jsp.Document migrate(Jsp.Document document, List<Result> results) {
        Set<String> migrated = new LinkedHashSet<>();
        for (Result result : results) {
            if (result.status() == Status.MIGRATED) {
                migrated.add(result.name());
            }
        }
        if (migrated.isEmpty()) {
            return document;
        }
        return document.withNodes(migrate(document.getNodes(), migrated));
    }

    List<Result> withUseCounts(List<Result> results) {
        List<Result> counted = new ArrayList<>();
        for (Result result : results) {
            counted.add(new Result(result.name(), result.status(), result.reason(), result.sites(),
                    convertedUses.getOrDefault(result.name(), 0)));
        }
        return counted;
    }

    private List<Jsp.Content> migrate(List<Jsp.Content> nodes, Set<String> migrated) {
        return ListUtils.map(nodes, node -> {
            if (node instanceof Jsp.Scriptlet) {
                return mirror((Jsp.Scriptlet) node, migrated);
            }
            if (node instanceof Jsp.ExpressionScriptlet) {
                String el = toEl(((Jsp.ExpressionScriptlet) node).getCodeSource(), migrated);
                return el == null ? node :
                        new Jsp.ExpressionLanguage(randomId(), node.getPrefix(), Markers.EMPTY,
                                Jsp.ExpressionLanguage.Type.IMMEDIATE, el);
            }
            if (node instanceof Jsp.Tag) {
                Jsp.Tag tag = (Jsp.Tag) node;
                if (convertTagAttributes) {
                    tag = tag.withAttributes(ListUtils.map(tag.getAttributes(), attribute -> {
                        Matcher whole = WHOLE_EXPRESSION.matcher(attribute.getValue().getValue());
                        if (!whole.matches()) {
                            return attribute;
                        }
                        String el = toEl(whole.group(1), migrated);
                        return el == null ? attribute :
                                attribute.withValue(attribute.getValue().withValue("${" + el + "}"));
                    }));
                }
                if (tag.getBody() != null) {
                    tag = tag.withBody(migrate(tag.getBody(), migrated));
                }
                return tag;
            }
            return node;
        });
    }

    /**
     * Inserts {@code pageContext.setAttribute("x", x);} after each recognized write of a migrated
     * variable (unless it's already there).
     */
    private Jsp.Scriptlet mirror(Jsp.Scriptlet scriptlet, Set<String> migrated) {
        List<JavaStatements.Site> sites = sitesByScriptlet.get(scriptlet.getId());
        if (sites == null) {
            return scriptlet;
        }
        String code = scriptlet.getCodeSource();
        List<JavaStatements.Site> inserts = new ArrayList<>();
        for (JavaStatements.Site site : sites) {
            if (migrated.contains(site.name()) && site.insertAt() >= 0 &&
                !mirroredAt(code, site.insertAt(), site.name())) {
                inserts.add(site);
            }
        }
        if (inserts.isEmpty()) {
            return scriptlet;
        }
        inserts.sort((a, b) -> Integer.compare(b.insertAt(), a.insertAt()));
        StringBuilder out = new StringBuilder(code);
        for (JavaStatements.Site site : inserts) {
            out.insert(site.insertAt(), " pageContext.setAttribute(\"" + site.name() + "\", " + site.name() + ");");
        }
        return scriptlet.withCode(out.toString());
    }

    private static boolean mirroredAt(String code, int at, String name) {
        return Pattern.compile("^\\s*pageContext\\s*\\.\\s*setAttribute\\s*\\(\\s*\"" + Pattern.quote(name) +
                               "\"\\s*,\\s*" + Pattern.quote(name) + "\\s*\\)\\s*;")
                .matcher(code.substring(at)).find();
    }

    /**
     * @return the EL body for an expression that only outputs a migrated variable or a getter
     * chain on it, e.g. {@code user.address.city} for {@code user.getAddress().getCity()}.
     */
    private @Nullable String toEl(String code, Set<String> migrated) {
        Matcher m = GETTER_CHAIN_ONLY.matcher(code);
        if (!m.matches() || !migrated.contains(m.group(1))) {
            return null;
        }
        StringBuilder el = new StringBuilder(m.group(1));
        Matcher getter = GETTER.matcher(m.group(2));
        while (getter.find()) {
            String property = getter.group(1);
            el.append('.').append(property.length() > 1 && Character.isUpperCase(property.charAt(1)) ?
                    property : Character.toLowerCase(property.charAt(0)) + property.substring(1));
        }
        convertedUses.merge(m.group(1), 1, Integer::sum);
        return el.toString();
    }
}
