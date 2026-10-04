package io.resys.orw.jsp.receipes.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class JspScriptletConversions extends DataTable<JspScriptletConversions.Row> {

    public JspScriptletConversions(Recipe recipe) {
        super(recipe, "JSP scriptlet conversions",
                "Each scriptlet construct considered for conversion to JSTL/EL: what it became, or why it stayed Java.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The page.")
        String sourcePath;

        @Column(displayName = "Line",
                description = "The 1-based line where the construct starts.")
        int line;

        @Column(displayName = "Construct",
                description = "`if`, `if/else`, `for-each`, `for`, `set` (a variable assignment), `expression` " +
                              "(a `<%= %>` output), or `page`.")
        String construct;

        @Column(displayName = "Status",
                description = "`CONVERTED` or `SKIPPED`.")
        String status;

        @Column(displayName = "Detail",
                description = "For a conversion, the EL/JSTL produced; otherwise why it stayed Java.")
        String detail;
    }
}
