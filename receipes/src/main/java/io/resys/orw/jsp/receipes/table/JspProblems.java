package io.resys.orw.jsp.receipes.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class JspProblems extends DataTable<JspProblems.Row> {

    public JspProblems(Recipe recipe) {
        super(recipe, "JSP problems",
                "Structural and readability problems found in JSP pages.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The page that was analyzed.")
        String sourcePath;

        @Column(displayName = "File",
                description = "The file the problem is in: the page itself, or a file it statically includes.")
        String file;

        @Column(displayName = "Line",
                description = "The 1-based line number of the problem in `File`.")
        int line;

        @Column(displayName = "Rule",
                description = "The kind of problem, e.g. `MISSING_END_TAG` or `SCRIPTLET_IN_HTML_TAG`.")
        String rule;

        @Column(displayName = "Message",
                description = "A description of the problem.")
        String message;
    }
}
