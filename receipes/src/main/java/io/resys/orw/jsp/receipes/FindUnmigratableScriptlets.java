package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.JspIsoVisitor;
import io.resys.orw.jsp.receipes.table.JspManualMigrations;
import io.resys.orw.jsp.receipes.table.JspMigrationEffort;
import io.resys.orw.jsp.tree.Jsp;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.marker.SearchResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Finds the Java in JSP pages that the automated scriptlet migration can't remove, and says why and
 * what kind of manual work it needs. The page itself is only marked, never changed.
 * <p>
 * For each page it simulates {@link MigrateJavaVariablesToPageAttributes} (with {@code mirrorAll})
 * followed by {@link ConvertScriptletsToJstl}, then checks each Java construct of the page -
 * scriptlet, {@code <%= %>} expression, {@code <%! %>} declaration, and {@code <%= %>} in a custom
 * tag attribute - against the result:
 * <ul>
 *     <li>{@code MIGRATABLE}: the migration would remove it; running those two recipes is enough.</li>
 *     <li>{@code MANUAL}: it would remain Java. It is marked in the page and listed with the reasons
 *     the migration gave (e.g. "condition 'u.isActive()': method call 'isActive(...)' can't be
 *     translated"), its categories of work, and a hint.</li>
 * </ul>
 * Categories, most specific first: {@code DECLARATION}, {@code DATA_ACCESS},
 * {@code REQUEST_SESSION_STATE}, {@code RESPONSE_CONTROL}, {@code OUTPUT_WRITING},
 * {@code EXCEPTION_HANDLING}, {@code STATIC_CALL}, {@code OBJECT_CREATION}, {@code CONTROL_FLOW},
 * {@code BLOCK_DELIMITER} (a <code>}</code> or {@code else} of a block that stays Java),
 * {@code VARIABLE}, {@code EXPRESSION}, {@code TAG_ATTRIBUTE}, {@code OTHER}. They're recognized by
 * text patterns, so they're a guide, not a guarantee.
 * <p>
 * Results go to the {@link JspManualMigrations} data table (one row per construct) and the
 * {@link JspMigrationEffort} data table (one row per page). Run it on untouched pages to see what
 * the migration will leave, or after the migration to list what's left. It analyzes each file's own
 * content: an included file is analyzed as a page of its own.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class FindUnmigratableScriptlets extends Recipe {

    transient JspManualMigrations constructs = new JspManualMigrations(this);
    transient JspMigrationEffort effort = new JspMigrationEffort(this);

    String displayName = "Find scriptlets that can't be migrated to JSTL";

    String description = "Lists the Java in JSP pages that the automated migration (variables to page attributes, " +
                          "then scriptlets to JSTL) can't remove, with the reason, the kind of manual work it needs, " +
                          "and a hint, and marks it in the page.";

    @Option(displayName = "JSTL version",
            description = "The JSTL version the migration would target, as for ConvertScriptletsToJstl. With `1.0`, " +
                          "which has no functions library, `size()`/`length()` can't be migrated. Defaults to `1.2`.",
            example = "1.2",
            valid = {"1.0", "1.1", "1.2", "2.0", "3.0"},
            required = false)
    @Nullable
    String jstlVersion;

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        String functionsPrefix = "1.0".equals(jstlVersion) ? null : "fn";
        return new JspIsoVisitor<ExecutionContext>() {
            @Override
            public Jsp.Document visitDocument(Jsp.Document document, ExecutionContext ctx) {
                String path = document.getSourcePath().toString();
                List<ManualMigrationAnalyzer.Finding> findings = ManualMigrationAnalyzer.analyze(document, functionsPrefix);
                JspPositions positions = JspPositions.of(document);
                Map<UUID, String> messages = new HashMap<>();
                int manual = 0;
                int manualLines = 0;
                for (ManualMigrationAnalyzer.Finding finding : findings) {
                    String categories = finding.categories().stream().map(Enum::name).collect(Collectors.joining(", "));
                    String hint = finding.categories().isEmpty() ? "" : finding.categories().get(0).hint;
                    constructs.insertRow(ctx, new JspManualMigrations.Row(path, positions.line(finding.node(), 0),
                            finding.kind(), finding.migratable() ? "MIGRATABLE" : "MANUAL", categories,
                            String.join("; ", finding.reasons()), hint, oneLine(finding.code())));
                    if (!finding.migratable()) {
                        manual++;
                        manualLines += (int) finding.code().lines().filter(l -> !l.isBlank()).count();
                        messages.merge(finding.node().getId(),
                                "Manual migration (" + categories + "): " + finding.reasons().get(0),
                                (a, b) -> a + "; " + b);
                    }
                }
                if (!findings.isEmpty()) {
                    effort.insertRow(ctx, new JspMigrationEffort.Row(path, findings.size(), findings.size() - manual,
                            manual, manualLines));
                }
                getCursor().putMessage("manual", messages);
                return super.visitDocument(document, ctx);
            }

            @Override
            public Jsp.Scriptlet visitScriptlet(Jsp.Scriptlet scriptlet, ExecutionContext ctx) {
                return mark(super.visitScriptlet(scriptlet, ctx));
            }

            @Override
            public Jsp.ExpressionScriptlet visitExpressionScriptlet(Jsp.ExpressionScriptlet expression,
                                                                    ExecutionContext ctx) {
                return mark(super.visitExpressionScriptlet(expression, ctx));
            }

            @Override
            public Jsp.Declaration visitDeclaration(Jsp.Declaration declaration, ExecutionContext ctx) {
                return mark(super.visitDeclaration(declaration, ctx));
            }

            @Override
            public Jsp.Attribute visitAttribute(Jsp.Attribute attribute, ExecutionContext ctx) {
                return mark(super.visitAttribute(attribute, ctx));
            }

            private <T extends Jsp> T mark(T node) {
                Map<UUID, String> messages = getCursor().getNearestMessage("manual");
                if (messages == null || getCursor().firstEnclosing(Jsp.IncludedFile.class) != null) {
                    return node;
                }
                String message = messages.get(node.getId());
                return message == null ? node : SearchResult.found(node, message);
            }
        };
    }

    private static String oneLine(String code) {
        String line = code.strip().replaceAll("\\s+", " ");
        return line.length() <= 120 ? line : line.substring(0, 117) + "...";
    }
}
