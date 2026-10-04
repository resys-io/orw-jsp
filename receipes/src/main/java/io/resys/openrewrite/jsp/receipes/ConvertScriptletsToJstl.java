package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.JspIsoVisitor;
import io.resys.openrewrite.jsp.receipes.table.JspScriptletConversions;
import io.resys.openrewrite.jsp.tree.Jsp;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.openrewrite.Tree.randomId;

/**
 * Converts scriptlets to JSTL and EL where that keeps the page's behavior:
 * <ul>
 *     <li>{@code <% if (c) { %>...<% } %>} to {@code <c:if test="${...}">}, and if/else-if/else
 *     chains to {@code <c:choose>}/{@code <c:when>}/{@code <c:otherwise>};</li>
 *     <li>{@code <% for (T x : items) { %>} to {@code <c:forEach var="x" items="${...}">}, and simple
 *     counting loops {@code for (int i = a; i < b; i++)} to {@code <c:forEach var="i" begin end>};</li>
 *     <li>scriptlets that only assign a variable mirrored into a page attribute (see
 *     {@link MigrateJavaVariablesToPageAttributes}), when no other Java code uses it anymore, to
 *     {@code <c:set var value>};</li>
 *     <li>{@code <%= expr %>} outputs to {@code ${...}}.</li>
 * </ul>
 * Java expressions are translated by {@link JavaToEl}; one outside its safe subset leaves its
 * construct as Java. EL can only see page attributes, so a Java variable is only usable once it's
 * mirrored into one: run {@link MigrateJavaVariablesToPageAttributes} with {@code mirrorAll} first.
 * A block's opening and closing scriptlets must be siblings (the block mustn't cross a tag
 * boundary); statements before the opener or after the closing <code>}</code> stay as a smaller
 * scriptlet. A loop stays Java if Java code in its body still uses the loop variable.
 * <p>
 * Every construct considered is listed, converted or with the reason it wasn't, in the
 * {@link JspScriptletConversions} data table. The JSTL core (and, for {@code fn:length}, functions)
 * taglib is declared if the page (or an included file) doesn't already declare it.
 * <p>
 * Needs EL: with JSTL 1.1+ nothing is changed on {@code isELIgnored} pages, nor anywhere if a parsed
 * {@code web.xml} declares a Servlet version before 2.4. JSTL 1.0 evaluates EL itself, so it works
 * there too, with outputs written as {@code <c:out value="${...}" escapeXml="false"/>}.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class ConvertScriptletsToJstl extends ScanningRecipe<MigrateJavaVariablesToPageAttributes.WebXml> {

    transient JspScriptletConversions table = new JspScriptletConversions(this);

    String displayName = "Convert scriptlets to JSTL";

    String description = "Converts scriptlet if/else and loops to `<c:if>`/`<c:choose>`/`<c:forEach>`, variable " +
                          "assignments to `<c:set>`, and `<%= %>` outputs to EL, where the Java expressions have " +
                          "an equivalent EL form. Everything else stays Java and is reported.";

    @Option(displayName = "JSTL version",
            description = "Which JSTL the application has, which decides the taglib URIs: `1.0` " +
                          "(`http://java.sun.com/jstl/core`; no functions library; works with container EL off), " +
                          "`1.1`/`1.2` (`http://java.sun.com/jsp/jstl/core`), or `3.0` (`jakarta.tags.core`). " +
                          "Defaults to `1.2`.",
            example = "1.2",
            valid = {"1.0", "1.1", "1.2", "2.0", "3.0"},
            required = false)
    @Nullable
    String jstlVersion;

    @Option(displayName = "Convert outputs",
            description = "Also convert `<%= %>` outputs to EL. Defaults to `true`.",
            required = false)
    @Nullable
    Boolean convertOutputs;

    @Override
    public MigrateJavaVariablesToPageAttributes.WebXml getInitialValue(ExecutionContext ctx) {
        return new MigrateJavaVariablesToPageAttributes.WebXml();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(MigrateJavaVariablesToPageAttributes.WebXml acc) {
        return new MigrateJavaVariablesToPageAttributes(null, null).getScanner(acc);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(MigrateJavaVariablesToPageAttributes.WebXml acc) {
        String version = jstlVersion == null ? "1.2" : jstlVersion;
        boolean jstl10 = "1.0".equals(version);
        String coreUri = jstl10 ? "http://java.sun.com/jstl/core" :
                "3.0".equals(version) ? "jakarta.tags.core" : "http://java.sun.com/jsp/jstl/core";
        String functionsUri = jstl10 ? null :
                "3.0".equals(version) ? "jakarta.tags.functions" : "http://java.sun.com/jsp/jstl/functions";

        return new JspIsoVisitor<ExecutionContext>() {
            @Override
            public Jsp.Document visitDocument(Jsp.Document document, ExecutionContext ctx) {
                String path = document.getSourcePath().toString();
                String elOff = acc.elDisabled != null ? acc.elDisabled :
                        JavaVariableMigrator.elIgnored(document) ? "the page sets isELIgnored=\"true\"" : null;
                if (elOff != null && !jstl10) {
                    table.insertRow(ctx, new JspScriptletConversions.Row(path, 1, "page", "SKIPPED",
                            elOff + "; JSTL " + version + " needs container EL (JSTL 1.0 doesn't)"));
                    return document;
                }

                Map<String, String> prefixByUri = new HashMap<>();
                Map<String, String> uriByPrefix = new HashMap<>();
                collectTaglibs(document.getNodes(), prefixByUri, uriByPrefix);
                String corePrefix = prefix(coreUri, "c", prefixByUri, uriByPrefix);
                String functionsPrefix = functionsUri == null ? null : prefix(functionsUri, "fn", prefixByUri, uriByPrefix);
                if (corePrefix == null) {
                    table.insertRow(ctx, new JspScriptletConversions.Row(path, 1, "page", "SKIPPED",
                            "the prefix 'c' is taken by another tag library and the JSTL core library isn't declared"));
                    return document;
                }

                Set<String> elVisible = ScriptletToJstlConverter.elVisibleNames(document.getNodes());
                ScriptletToJstlConverter converter = new ScriptletToJstlConverter(corePrefix, functionsPrefix,
                        elOff != null, !Boolean.FALSE.equals(convertOutputs), elVisible);
                List<Jsp.Content> nodes = converter.convert(document.getNodes(), elVisible);
                nodes = converter.convertAssignments(nodes, elVisible);

                JspPositions positions = JspPositions.of(document);
                for (ScriptletToJstlConverter.Conversion conversion : converter.conversions) {
                    table.insertRow(ctx, new JspScriptletConversions.Row(path, positions.line(conversion.node(), 0),
                            conversion.construct(), conversion.converted() ? "CONVERTED" : "SKIPPED",
                            conversion.detail()));
                }
                if (converter.conversions.stream().noneMatch(ScriptletToJstlConverter.Conversion::converted)) {
                    return document;
                }

                // Each added taglib directive on a line of its own, at the top of the page.
                List<Jsp.Content> declarations = new ArrayList<>();
                if (converter.usedCore && !prefixByUri.containsKey(coreUri)) {
                    declarations.add(taglib(corePrefix, coreUri));
                    declarations.add(new Jsp.Text(randomId(), Markers.EMPTY, "\n"));
                }
                if (converter.usedFunctions && functionsPrefix != null && !prefixByUri.containsKey(functionsUri)) {
                    declarations.add(taglib(functionsPrefix, functionsUri));
                    declarations.add(new Jsp.Text(randomId(), Markers.EMPTY, "\n"));
                }
                if (!declarations.isEmpty()) {
                    declarations.addAll(nodes);
                    nodes = declarations;
                }
                return document.withNodes(nodes);
            }
        };
    }

    private static void collectTaglibs(List<Jsp.Content> nodes, Map<String, String> prefixByUri,
                                       Map<String, String> uriByPrefix) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Directive) {
                Jsp.Directive directive = (Jsp.Directive) node;
                if ("taglib".equals(directive.getName())) {
                    String prefix = null;
                    String uri = null;
                    for (Jsp.Attribute attribute : directive.getAttributes()) {
                        if ("prefix".equals(attribute.getName())) {
                            prefix = attribute.getValue().getValue();
                        } else if ("uri".equals(attribute.getName())) {
                            uri = attribute.getValue().getValue();
                        }
                    }
                    if (prefix != null && uri != null) {
                        prefixByUri.putIfAbsent(uri, prefix);
                        uriByPrefix.putIfAbsent(prefix, uri);
                    }
                }
                if (directive.getIncludedFile() != null) {
                    collectTaglibs(directive.getIncludedFile().getNodes(), prefixByUri, uriByPrefix);
                }
            }
        }
    }

    private static @Nullable String prefix(String uri, String preferred, Map<String, String> prefixByUri,
                                           Map<String, String> uriByPrefix) {
        String declared = prefixByUri.get(uri);
        if (declared != null) {
            return declared;
        }
        return uriByPrefix.containsKey(preferred) ? null : preferred;
    }

    private static Jsp.Directive taglib(String prefix, String uri) {
        return new Jsp.Directive(randomId(), "", Markers.EMPTY, " ", "taglib", List.of(
                new Jsp.Attribute(randomId(), " ", Markers.EMPTY, "prefix", "",
                        new Jsp.Attribute.Value(randomId(), "", Markers.EMPTY, '"', prefix)),
                new Jsp.Attribute(randomId(), " ", Markers.EMPTY, "uri", "",
                        new Jsp.Attribute.Value(randomId(), "", Markers.EMPTY, '"', uri))), " ", null);
    }
}
