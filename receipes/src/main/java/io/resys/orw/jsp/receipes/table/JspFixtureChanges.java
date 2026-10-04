package io.resys.orw.jsp.receipes.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class JspFixtureChanges extends DataTable<JspFixtureChanges.Row> {

    public JspFixtureChanges(Recipe recipe) {
        super(recipe, "JSP fixture changes",
                "The tester fixtures the recipe created, updated, deleted, or couldn't read.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Fixture",
                description = "The fixture file.")
        String fixture;

        @Column(displayName = "Page",
                description = "The page it is for, as in its \"page\".")
        String page;

        @Column(displayName = "Action",
                description = "`CREATED` (a skeleton for a page without fixtures), `UPDATED` (inputs the page " +
                              "reads added), `DELETED` (its page no longer exists), or `SKIPPED` (not valid " +
                              "fixture JSON, or no \"page\").")
        String action;

        @Column(displayName = "Details",
                description = "What was added, what couldn't be (from \"_todo\"), or why it was deleted or skipped.")
        String details;
    }
}
