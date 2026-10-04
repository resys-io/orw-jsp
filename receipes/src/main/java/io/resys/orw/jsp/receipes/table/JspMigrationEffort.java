package io.resys.orw.jsp.receipes.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class JspMigrationEffort extends DataTable<JspMigrationEffort.Row> {

    public JspMigrationEffort(Recipe recipe) {
        super(recipe, "JSP migration effort",
                "Per page: how much of its Java the automated scriptlet migration would remove, and how much is left " +
                "for manual work.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The page.")
        String sourcePath;

        @Column(displayName = "Java constructs",
                description = "Scriptlets, expressions, declarations, and `<%= %>` in tag attributes.")
        int javaConstructs;

        @Column(displayName = "Migratable",
                description = "Constructs the automated migration would remove.")
        int migratable;

        @Column(displayName = "Manual",
                description = "Constructs needing manual work.")
        int manual;

        @Column(displayName = "Manual Java lines",
                description = "Non-blank lines of Java in the manual constructs.")
        int manualJavaLines;
    }
}
