package io.resys.orw.jsp.receipes.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class JspPageInventory extends DataTable<JspPageInventory.Row> {

    public JspPageInventory(Recipe recipe) {
        super(recipe, "JSP page inventory",
                "Size and composition of each page, as a rough measure of its migration effort.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The page.")
        String sourcePath;

        @Column(displayName = "Lines",
                description = "Lines in the page (not counting included files).")
        int lines;

        @Column(displayName = "Scriptlets",
                description = "`<% %>` scriptlets.")
        int scriptlets;

        @Column(displayName = "Java lines",
                description = "Non-blank lines of Java code in scriptlets and declarations.")
        int javaLines;

        @Column(displayName = "Expressions",
                description = "`<%= %>` expressions, including those in tag attribute values.")
        int expressions;

        @Column(displayName = "Declarations",
                description = "`<%! %>` declarations.")
        int declarations;

        @Column(displayName = "EL expressions",
                description = "`${}`/`#{}` expressions, including those in tag attribute values.")
        int elExpressions;

        @Column(displayName = "Custom tags",
                description = "Custom tags (everything prefixed except `jsp:` standard actions).")
        int customTags;

        @Column(displayName = "Includes",
                description = "`<%@ include %>` directives and `<jsp:include>` actions.")
        int includes;

        @Column(displayName = "Tag libraries",
                description = "The taglib uris (or `tagdir:` paths) the page declares, comma-separated.")
        String tagLibraries;
    }
}
