package io.resys.orw.jsp.receipes.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class JspManualMigrations extends DataTable<JspManualMigrations.Row> {

    public JspManualMigrations(Recipe recipe) {
        super(recipe, "JSP manual migrations",
                "Each Java construct in a page: whether the automated scriptlet migration would remove it, and if not, " +
                "why, what kind of work it needs, and how to approach it.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The page.")
        String sourcePath;

        @Column(displayName = "Line",
                description = "The 1-based line where the construct starts.")
        int line;

        @Column(displayName = "Kind",
                description = "`scriptlet`, `expression`, `declaration`, or `tag attribute` (a `<%= %>` in a custom " +
                              "tag's attribute value).")
        String kind;

        @Column(displayName = "Status",
                description = "`MIGRATABLE` if MigrateJavaVariablesToPageAttributes (mirrorAll) followed by " +
                              "ConvertScriptletsToJstl would remove it; `MANUAL` otherwise.")
        String status;

        @Column(displayName = "Categories",
                description = "For `MANUAL`: the kinds of work it needs, most specific first, e.g. `DATA_ACCESS`, " +
                              "`RESPONSE_CONTROL`, `STATIC_CALL`, `CONTROL_FLOW`; comma-separated.")
        String categories;

        @Column(displayName = "Reasons",
                description = "For `MANUAL`: why the automated migration leaves it; semicolon-separated.")
        String reasons;

        @Column(displayName = "Hint",
                description = "For `MANUAL`: how to approach the first category.")
        String hint;

        @Column(displayName = "Code",
                description = "The Java code, on one line, shortened to 120 characters.")
        String code;
    }
}
