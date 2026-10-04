package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.JspIsoVisitor;
import io.resys.openrewrite.jsp.receipes.table.JspStaticMethodCalls;
import io.resys.openrewrite.jsp.tree.Jsp;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.marker.SearchResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds static method calls in the Java code of JSP pages - scriptlets, {@code <%= %>} expressions,
 * {@code <%! %>} declarations, and {@code <%= %>} in custom tag attribute values - which a migration
 * to a model-driven view (Spring MVC + Thymeleaf) has to move out of the view. Each call is marked
 * in the page and listed in the {@link JspStaticMethodCalls} data table. Changes nothing else.
 * <p>
 * Without a type checker, calls are recognized by Java naming conventions: {@code Class.method(...)}
 * with a capitalized class name (or {@code Outer.Inner.method(...)}, {@code Class.<T>method(...)}),
 * or {@code com.acme.Util.method(...)} with a lowercase package. Not counted: calls on a capitalized
 * variable the page declares, constructors ({@code new Outer.Inner(...)}), instance methods of
 * constants ({@code Status.ACTIVE.name()}), and anything in string literals or comments. The class
 * is resolved through the page's {@code <%@ page import %>}s (including those of statically
 * included files): an explicit import or a {@code java.lang} class resolves it; a wildcard import
 * leaves candidates.
 * <p>
 * {@link #getExclusions()} suppresses calls a migration can keep (e.g. Thymeleaf can call static
 * methods with {@code T(...)}) or doesn't care about.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class FindStaticMethodCalls extends Recipe {

    private static final Pattern EXPRESSION_IN_TEXT = Pattern.compile("<%=(.*?)%>", Pattern.DOTALL);

    transient JspStaticMethodCalls table = new JspStaticMethodCalls(this);

    String displayName = "Find static method calls in JSP pages";

    String description = "Finds static method calls in scriptlets, expressions, declarations, and `<%= %>` tag " +
                          "attribute values of JSP pages, which a migration has to move out of the view. " +
                          "Calls matching an exclusion pattern are not reported.";

    @Option(displayName = "Exclusions",
            description = "Calls not to report, as patterns matched against `fully.qualified.Class.method`: " +
                          "`*` matches within one name segment, `**` across segments. E.g. `java.lang.Math.*`, " +
                          "`java.lang.Integer.parseInt`, `org.apache.commons.lang.StringUtils.*`, `com.acme.util.**`. " +
                          "A call whose class can't be resolved unambiguously is excluded if any of its candidate " +
                          "classes (or the class as written) matches.",
            example = "java.lang.Math.*",
            required = false)
    @Nullable
    List<String> exclusions;

    private record Context(JspPositions positions, Map<String, String> imports, Set<String> wildcardPackages,
                           Set<String> localNames, List<Pattern> exclusions) {
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        List<Pattern> patterns = new ArrayList<>();
        if (exclusions != null) {
            for (String exclusion : exclusions) {
                if (!exclusion.isBlank()) {
                    patterns.add(StaticCalls.exclusion(exclusion.trim()));
                }
            }
        }
        return new JspIsoVisitor<ExecutionContext>() {
            @Override
            public Jsp.Document visitDocument(Jsp.Document document, ExecutionContext ctx) {
                Map<String, String> imports = new HashMap<>();
                Set<String> wildcards = new LinkedHashSet<>();
                Set<String> locals = new LinkedHashSet<>();
                collectDeclarations(document.getNodes(), imports, wildcards, locals);
                getCursor().putMessage("context",
                        new Context(JspPositions.of(document), imports, wildcards, locals, patterns));
                return super.visitDocument(document, ctx);
            }

            @Override
            public Jsp.Scriptlet visitScriptlet(Jsp.Scriptlet scriptlet, ExecutionContext ctx) {
                return report(super.visitScriptlet(scriptlet, ctx), scriptlet.getCode(), 0, "scriptlet", ctx);
            }

            @Override
            public Jsp.ExpressionScriptlet visitExpressionScriptlet(Jsp.ExpressionScriptlet expression,
                                                                    ExecutionContext ctx) {
                return report(super.visitExpressionScriptlet(expression, ctx), expression.getCode(), 0,
                        "expression", ctx);
            }

            @Override
            public Jsp.Declaration visitDeclaration(Jsp.Declaration declaration, ExecutionContext ctx) {
                return report(super.visitDeclaration(declaration, ctx), declaration.getCode(), 0,
                        "declaration", ctx);
            }

            @Override
            public Jsp.Attribute visitAttribute(Jsp.Attribute attribute, ExecutionContext ctx) {
                Jsp.Attribute a = super.visitAttribute(attribute, ctx);
                if (!(getCursor().getParentTreeCursor().getValue() instanceof Jsp.Tag)) {
                    return a;
                }
                String value = a.getValue().getValue();
                String beforeValue = a.getPrefix() + a.getName() + a.getBeforeEquals() + "=" +
                                     a.getValue().getPrefix() + a.getValue().getQuote();
                Matcher expression = EXPRESSION_IN_TEXT.matcher(value);
                while (expression.find()) {
                    a = report(a, expression.group(1),
                            JspPositions.newlines(beforeValue + value, beforeValue.length() + expression.start(1)),
                            "tag attribute", ctx);
                }
                return a;
            }

            private <T extends Jsp> T report(T node, String code, int newlinesBeforeCode, String context,
                                             ExecutionContext ctx) {
                Context c = getCursor().getNearestMessage("context");
                if (c == null || getCursor().firstEnclosing(Jsp.IncludedFile.class) != null) {
                    return node; // analyzed as its own file instead
                }
                List<String> messages = new ArrayList<>();
                for (StaticCalls.Call call : StaticCalls.find(code, c.localNames())) {
                    List<String> candidates = StaticCalls.candidates(call.writtenClass(), c.imports(),
                            c.wildcardPackages());
                    if (StaticCalls.excluded(candidates, call.method(), c.exclusions())) {
                        continue;
                    }
                    String unique = StaticCalls.unique(candidates);
                    String className = unique != null ? unique : call.writtenClass();
                    int line = c.positions().line(node, newlinesBeforeCode +
                                                        JspPositions.newlines(code, call.offset()));
                    table.insertRow(ctx, new JspStaticMethodCalls.Row(
                            getCursor().firstEnclosingOrThrow(Jsp.Document.class).getSourcePath().toString(),
                            line, className, call.method(), unique != null ? "" : String.join(", ", candidates),
                            context));
                    messages.add("Static method call " + className + "." + call.method() + "()");
                }
                return messages.isEmpty() ? node : SearchResult.mergingFound(node, String.join(", ", messages));
            }
        };
    }

    /**
     * Collects the page's imports and the names its Java code declares, including statically
     * included files, since they're compiled into the same servlet.
     */
    private static void collectDeclarations(List<Jsp.Content> nodes, Map<String, String> imports,
                                            Set<String> wildcards, Set<String> locals) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Directive) {
                Jsp.Directive directive = (Jsp.Directive) node;
                if ("page".equals(directive.getName())) {
                    for (Jsp.Attribute attribute : directive.getAttributes()) {
                        if ("import".equals(attribute.getName())) {
                            for (String entry : attribute.getValue().getValue().split(",")) {
                                String className = entry.trim();
                                if (className.endsWith(".*")) {
                                    String pkg = className.substring(0, className.length() - 2);
                                    if (!"java.lang".equals(pkg)) {
                                        wildcards.add(pkg);
                                    }
                                } else if (className.lastIndexOf('.') > 0) {
                                    imports.put(className.substring(className.lastIndexOf('.') + 1), className);
                                }
                            }
                        }
                    }
                }
                if (directive.getIncludedFile() != null) {
                    collectDeclarations(directive.getIncludedFile().getNodes(), imports, wildcards, locals);
                }
            } else if (node instanceof Jsp.Scriptlet) {
                addLocals(((Jsp.Scriptlet) node).getCode(), false, locals);
            } else if (node instanceof Jsp.Declaration) {
                addLocals(((Jsp.Declaration) node).getCode(), true, locals);
            } else if (node instanceof Jsp.Tag && ((Jsp.Tag) node).getBody() != null) {
                collectDeclarations(((Jsp.Tag) node).getBody(), imports, wildcards, locals);
            }
        }
    }

    private static void addLocals(String code, boolean declaration, Set<String> locals) {
        for (JspVariables.Definition definition : JspVariables.javaDeclarations(code, declaration, 0)) {
            locals.add(definition.name());
        }
    }
}
