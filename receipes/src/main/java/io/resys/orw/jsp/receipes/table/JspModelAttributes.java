package io.resys.orw.jsp.receipes.table;

import lombok.Value;
import org.openrewrite.Column;
import org.openrewrite.DataTable;
import org.openrewrite.Recipe;

public class JspModelAttributes extends DataTable<JspModelAttributes.Row> {

    public JspModelAttributes(Recipe recipe) {
        super(recipe, "JSP model attributes",
                "The inputs each page expects but doesn't define itself: model attributes, request parameters, " +
                "and Tiles attributes, with their type where known and the property paths the page reads.");
    }

    @Value
    public static class Row {
        @Column(displayName = "Source path",
                description = "The page.")
        String sourcePath;

        @Column(displayName = "Scope",
                description = "`REQUEST`, `SESSION`, or `APPLICATION` for an attribute read from that scope; `ANY` " +
                              "for one read from whichever scope has it (EL, `findAttribute`, Struts tags without " +
                              "`scope`); `PARAMETER` for a request parameter; `TILES` for a Tiles attribute.")
        String scope;

        @Column(displayName = "Name",
                description = "The attribute or parameter name. `(non-literal key: ...)` when Java code reads it " +
                              "by a non-literal key, e.g. a constant.")
        String name;

        @Column(displayName = "Type",
                description = "The type, if the page tells it: a cast or typed variable in Java, " +
                              "`<jsp:useBean>`, `<bean:define type>`, a Struts form bean, or `String`/`String[]` " +
                              "for parameters. Simple names are qualified using the page's imports. Empty if unknown.")
        String type;

        @Column(displayName = "Properties",
                description = "The property paths the page reads from it, e.g. `address.city`, `orders[].total` " +
                              "(`[]` is a collection element), with `: Type` where known; comma-separated.")
        String properties;

        @Column(displayName = "Read by",
                description = "How the page reads it, e.g. `EL`, `request.getAttribute`, `bean:write`, " +
                              "`struts-config form bean for action /save`; comma-separated.")
        String readBy;

        @Column(displayName = "Files",
                description = "Where it is read: the page and/or files it statically includes; comma-separated.")
        String files;
    }
}
