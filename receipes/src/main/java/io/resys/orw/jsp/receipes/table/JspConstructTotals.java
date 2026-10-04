package io.resys.orw.jsp.receipes.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class JspConstructTotals extends DataTable<JspConstructTotals.Row> {

    public JspConstructTotals(Recipe recipe) {
        super(recipe, "JSP construct totals",
                "Each JSP construct's use across all pages, most used first: what a migration has to cover.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Category",
                description = "`TAG`, `EL_FUNCTION`, `EL_IMPLICIT_OBJECT`, `DIRECTIVE`, `SCRIPTING`, or " +
                              "`JAVA_IMPLICIT_OBJECT`.")
        String category;

        @Column(displayName = "Construct",
                description = "The construct. Tags and EL functions are grouped by library and local name, " +
                              "and shown with the prefix first seen for them.")
        String construct;

        @Column(displayName = "Library",
                description = "For tags and EL functions, the taglib `uri`; empty otherwise.")
        String library;

        @Column(displayName = "Total count",
                description = "Uses across all pages.")
        int totalCount;

        @Column(displayName = "Page count",
                description = "How many pages use it.")
        int pageCount;
    }
}
