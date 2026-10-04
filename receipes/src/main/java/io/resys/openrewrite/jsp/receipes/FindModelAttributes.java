package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.JspIsoVisitor;
import io.resys.openrewrite.jsp.receipes.table.JspModelAttributes;
import io.resys.openrewrite.jsp.tree.Jsp;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Documents the inputs each JSP page expects but doesn't define itself - the model attributes a
 * controller has to provide, plus request parameters and Tiles attributes - with their type where
 * the page tells it and the property paths it reads from them. Changes nothing; the result is the
 * {@link JspModelAttributes} data table, one row per page and input.
 * <p>
 * Recognized reads:
 * <ul>
 *     <li>Java: {@code request}/{@code session}/{@code application}/{@code request.getSession()}/
 *     {@code getServletContext()}{@code .getAttribute("x")}, {@code pageContext.findAttribute("x")}
 *     and {@code pageContext.getAttribute("x", PageContext.REQUEST_SCOPE)} etc.,
 *     {@code request.getParameter("p")}/{@code getParameterValues("p")}. The type comes from a cast
 *     or the declared type of the variable it's assigned to; getter chains on that variable
 *     ({@code u.getAddress().getCity()}), Java for-each loops over it, and typed assignments from
 *     getters ({@code String n = u.getName();}) add property paths (and their types).</li>
 *     <li>EL: root names and property paths ({@code ${user.address.city}}, {@code ${user['name']}}),
 *     {@code requestScope}/{@code sessionScope}/{@code applicationScope} lookups, and
 *     {@code param}/{@code paramValues}. {@code <c:forEach var items>} makes {@code var} stand for
 *     the elements ({@code items[].name}).</li>
 *     <li>Struts 1: {@code name}/{@code property}/{@code scope} on {@code bean:write}, {@code bean:define},
 *     {@code bean:size}, the {@code logic:} comparison/presence tags, and {@code logic:iterate}
 *     (whose {@code type} gives the element type and whose {@code id} stands for the elements);
 *     {@code parameter} on {@code logic:} tags; {@code html:} input fields' {@code property} as a
 *     property of the enclosing {@code <html:form>}'s form bean (or of {@code name}), and
 *     {@code html:options}/{@code html:optionsCollection}. The form bean of
 *     {@code <html:form action="/save">} - its attribute name, scope, type, and, for a
 *     {@code DynaActionForm}, its properties - comes from any {@code struts-config.xml} parsed along
 *     with the pages (with OpenRewrite's XML parser); otherwise it is listed as
 *     {@code (form bean of action /save)}.</li>
 *     <li>Struts Tiles: {@code tiles:getAsString}, {@code tiles:insert attribute},
 *     {@code tiles:useAttribute}, {@code tiles:importAttribute}.</li>
 *     <li>{@code <jsp:useBean>} in request, session, or application scope (with its type), and
 *     {@code <jsp:getProperty>}/{@code <jsp:setProperty>}.</li>
 * </ul>
 * Names the page defines itself first ({@code <c:set var>}, {@code <bean:define>} of a literal,
 * iteration variables, page-scoped beans) are not inputs. Statically included files' reads count
 * as the including page's. This is text-level analysis, not a real Java or EL parser: unusual code
 * can be missed, and a type is only as precise as the page states it.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class FindModelAttributes extends ScanningRecipe<StrutsConfig> {

    transient JspModelAttributes table = new JspModelAttributes(this);

    String displayName = "Find JSP model attributes";

    String description = "Documents the model attributes (plus request parameters and Tiles attributes) each JSP page " +
                          "expects but doesn't define itself, with their types where the page tells them and the " +
                          "property paths it reads. Understands scriptlets, EL, JSTL, Struts 1 tags, and Struts " +
                          "form beans from struts-config.xml. Changes nothing.";

    @Override
    public StrutsConfig getInitialValue(ExecutionContext ctx) {
        return new StrutsConfig();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(StrutsConfig acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof Xml.Document && StrutsConfig.isStrutsConfig((Xml.Document) tree)) {
                    acc.add((Xml.Document) tree);
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(StrutsConfig acc) {
        return new JspIsoVisitor<ExecutionContext>() {
            @Override
            public Jsp.Document visitDocument(Jsp.Document document, ExecutionContext ctx) {
                Path page = document.getSourcePath();
                for (ModelAttributeCollector.Attribute attribute :
                        new ModelAttributeCollector(page, acc).collect(document)) {
                    table.insertRow(ctx, new JspModelAttributes.Row(
                            page.toString(),
                            attribute.scope.name(),
                            attribute.name,
                            attribute.type == null ? "" : attribute.type,
                            attribute.properties.entrySet().stream()
                                    .map(p -> p.getValue().isEmpty() ? p.getKey() : p.getKey() + ": " + p.getValue())
                                    .collect(Collectors.joining(", ")),
                            String.join(", ", attribute.evidence),
                            attribute.files.stream().map(Path::toString).collect(Collectors.joining(", "))));
                }
                return document;
            }
        };
    }
}
