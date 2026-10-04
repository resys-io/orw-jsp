package io.resys.orw.jsp.receipes.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class JspConstructUsage extends DataTable<JspConstructUsage.Row> {

    public JspConstructUsage(Recipe recipe) {
        super(recipe, "JSP construct usage",
                "How many times each page uses each JSP construct: tags, EL functions and implicit objects, " +
                "directives, scripting elements, and implicit objects used from Java code.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The page.")
        String sourcePath;

        @Column(displayName = "Category",
                description = "`TAG`, `EL_FUNCTION`, `EL_IMPLICIT_OBJECT`, `DIRECTIVE`, `SCRIPTING`, or " +
                              "`JAVA_IMPLICIT_OBJECT`.")
        String category;

        @Column(displayName = "Construct",
                description = "The construct as written on the page, e.g. `html:form`, `fn:length`, " +
                              "`sessionScope`, `include`, `scriptlet`, or `request`.")
        String construct;

        @Column(displayName = "Library",
                description = "For tags and EL functions, the taglib `uri` (or `tagdir:` path) the prefix is " +
                              "declared with on the page, `(JSP standard action)` for `jsp:`, or " +
                              "`(undeclared prefix)`. Empty for other categories.")
        String library;

        @Column(displayName = "Count",
                description = "How many times the page uses it.")
        int count;
    }
}
