package io.resys.orw.jsp.receipes.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class JspVariableMigrations extends DataTable<JspVariableMigrations.Row> {

    public JspVariableMigrations(Recipe recipe) {
        super(recipe, "JSP variable migrations",
                "Each Java variable a page declares in a scriptlet: whether it was mirrored into a page attribute " +
                "(with its outputs turned into EL), or why not.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The page.")
        String sourcePath;

        @Column(displayName = "Variable",
                description = "The Java variable name.")
        String variable;

        @Column(displayName = "Status",
                description = "`MIGRATED` or `SKIPPED`.")
        String status;

        @Column(displayName = "Reason",
                description = "Why it was skipped; empty if migrated.")
        String reason;

        @Column(displayName = "Declarations",
                description = "How many times the page declares it (e.g. several loops reusing a name).")
        int declarations;

        @Column(displayName = "Converted uses",
                description = "How many `<%= %>` outputs of it were turned into EL.")
        int convertedUses;
    }
}
