package io.resys.openrewrite.jsp.receipes.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class JspStaticMethodCalls extends DataTable<JspStaticMethodCalls.Row> {

    public JspStaticMethodCalls(Recipe recipe) {
        super(recipe, "JSP static method calls",
                "Static method calls in the Java code of JSP pages, which a migration has to move out of the view.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The page.")
        String sourcePath;

        @Column(displayName = "Line",
                description = "The 1-based line of the call.")
        int line;

        @Column(displayName = "Class",
                description = "The class, fully qualified through the page's imports when that's unambiguous; " +
                              "otherwise as written.")
        String className;

        @Column(displayName = "Method",
                description = "The method name.")
        String method;

        @Column(displayName = "Candidates",
                description = "When the class can't be resolved unambiguously (a wildcard import), the classes " +
                              "it may be, comma-separated; empty otherwise.")
        String candidates;

        @Column(displayName = "Context",
                description = "Where the call is: `scriptlet`, `expression`, `declaration`, or " +
                              "`tag attribute` (a `<%= %>` in a custom tag's attribute value).")
        String context;
    }
}
