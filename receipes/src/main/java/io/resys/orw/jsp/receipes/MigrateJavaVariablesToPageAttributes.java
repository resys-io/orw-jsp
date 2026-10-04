package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.JspIsoVisitor;
import io.resys.orw.jsp.receipes.table.JspVariableMigrations;
import io.resys.orw.jsp.tree.Jsp;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.xml.tree.Xml;

import java.util.List;

/**
 * Mirrors Java variables declared in scriptlets into page-scoped attributes, and turns the
 * {@code <%= %>} expressions that only output them into EL, so the rest of the page (and later
 * migration steps, such as replacing scriptlets with JSTL) can use them from EL:
 * <pre>{@code
 * <% String name = user.getName(); %>    ->  <% String name = user.getName(); pageContext.setAttribute("name", name); %>
 * <p><%= name %></p>                     ->  <p>${name}</p>
 * <% for (Order o : orders) { %>         ->  <% for (Order o : orders) { pageContext.setAttribute("o", o); %>
 *   <td><%= o.getTotal() %></td>         ->    <td>${o.total}</td>
 * }</pre>
 * The Java variable is kept, so other Java code is unaffected. A {@code setAttribute} is inserted
 * after each declaration with an initializer, at the start of each braced loop body declaring the
 * variable, and after each statement assigning it, so the attribute never goes stale. An output is
 * converted if it is just the variable or a chain of {@code get...()} getters on it.
 * <p>
 * A variable is left alone (and reported, in the {@link JspVariableMigrations} data table) if:
 * <ul>
 *     <li>it's written somewhere a statement can't follow (inside an expression, a braceless
 *     if/else/loop body, a case label), or declared by a loop without a braced body;</li>
 *     <li>the page already uses its name in EL or as a tag's {@code name}/{@code var}/{@code id},
 *     which a page attribute would shadow;</li>
 *     <li>its name is an EL reserved word or implicit object;</li>
 *     <li>it's a {@code <%! %>} field;</li>
 *     <li>no {@code <%= %>} outputs it, unless {@link #getMirrorAll()}.</li>
 * </ul>
 * {@code is...()} getters aren't converted (EL only recognizes them for primitive {@code boolean}).
 * Variables declared only in a statically included file are migrated in that file, not through the
 * page. Re-running the recipe recognizes its own {@code setAttribute}s and changes nothing more.
 * <p>
 * EL must be enabled: nothing is changed on a page with {@code isELIgnored="true"}, nor anywhere if a
 * {@code web.xml} declaring a Servlet version before 2.4 (in which EL is off by default) is parsed
 * along with the pages. One output difference remains: {@code <%= x %>} prints {@code null} for a
 * null value, {@code ${x}} prints nothing.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateJavaVariablesToPageAttributes extends ScanningRecipe<MigrateJavaVariablesToPageAttributes.WebXml> {

    transient JspVariableMigrations table = new JspVariableMigrations(this);

    String displayName = "Migrate JSP Java variables to page attributes";

    String description = "Mirrors Java variables declared in scriptlets into page-scoped attributes " +
                          "(`pageContext.setAttribute`) and turns `<%= %>` expressions that only output them " +
                          "into EL, so they can be used from EL and JSTL.";

    @Option(displayName = "Convert tag attributes",
            description = "Also convert a custom tag attribute value that is exactly `<%= x %>` to `${x}`. Only " +
                          "safe if the tags evaluate EL in that attribute (a JSP 2.0+ container with " +
                          "`rtexprvalue` attributes, or EL-aware tags). Defaults to `false`.",
            required = false)
    @Nullable
    Boolean convertTagAttributes;

    @Option(displayName = "Mirror all variables",
            description = "Mirror every migratable variable into a page attribute, not only those some " +
                          "`<%= %>` outputs, e.g. to use them from JSTL conditions later. Defaults to `false`.",
            required = false)
    @Nullable
    Boolean mirrorAll;

    public static class WebXml {
        /**
         * Why EL is off application-wide, if a parsed {@code web.xml} says so.
         */
        @Nullable
        String elDisabled;
    }

    @Override
    public WebXml getInitialValue(ExecutionContext ctx) {
        return new WebXml();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(WebXml acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof Xml.Document && "web-app".equals(((Xml.Document) tree).getRoot().getName())) {
                    Xml.Document webXml = (Xml.Document) tree;
                    String version = webXml.getRoot().getAttributes().stream()
                            .filter(a -> "version".equals(a.getKeyAsString()))
                            .map(Xml.Attribute::getValueAsString)
                            .findFirst().orElse(null);
                    if (version == null || olderThan24(version)) {
                        acc.elDisabled = webXml.getSourcePath() + " declares Servlet " +
                                         (version == null ? "2.3 or earlier (DTD-based)" : version) +
                                         ", in which EL is disabled by default";
                    }
                }
                return tree;
            }
        };
    }

    private static boolean olderThan24(String version) {
        try {
            String[] parts = version.trim().split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major < 2 || (major == 2 && minor < 4);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(WebXml acc) {
        return new JspIsoVisitor<ExecutionContext>() {
            @Override
            public Jsp.Document visitDocument(Jsp.Document document, ExecutionContext ctx) {
                JavaVariableMigrator migrator = new JavaVariableMigrator(
                        Boolean.TRUE.equals(convertTagAttributes), Boolean.TRUE.equals(mirrorAll));
                List<JavaVariableMigrator.Result> results = migrator.analyze(document);

                String elOff = acc.elDisabled != null ? acc.elDisabled :
                        JavaVariableMigrator.elIgnored(document) ? "the page sets isELIgnored=\"true\"" : null;
                Jsp.Document migrated = document;
                if (elOff == null) {
                    migrated = migrator.migrate(document, results);
                    results = migrator.withUseCounts(results);
                }
                for (JavaVariableMigrator.Result result : results) {
                    boolean skipped = elOff != null || result.status() == JavaVariableMigrator.Status.SKIPPED;
                    table.insertRow(ctx, new JspVariableMigrations.Row(document.getSourcePath().toString(),
                            result.name(), skipped ? "SKIPPED" : "MIGRATED",
                            elOff != null ? elOff : result.reason(), result.sites(), result.convertedUses()));
                }
                return migrated;
            }
        };
    }
}
