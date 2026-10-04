package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.tree.Jsp;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts the JSP constructs one page uses, for {@link JspInventory}. Only the page's own content
 * is counted; statically included files are counted as pages of their own. They are only read for
 * the taglib prefixes they declare, which apply to the including page too.
 */
final class JspInventoryCollector {

    enum Category {
        TAG, EL_FUNCTION, EL_IMPLICIT_OBJECT, DIRECTIVE, SCRIPTING, JAVA_IMPLICIT_OBJECT
    }

    /**
     * A construct, identified across pages: tags and EL functions by library and local name, since
     * the prefix is chosen per page.
     */
    record Key(Category category, String name, String library) {
    }

    static final String STANDARD_ACTION = "(JSP standard action)";
    static final String UNDECLARED = "(undeclared prefix)";

    private static final Pattern EL_FUNCTION_CALL =
            Pattern.compile("(?<![\\w$.])([A-Za-z_][\\w-]*):([A-Za-z_$][\\w$]*)\\s*\\(");

    private static final Pattern EL_IMPLICIT_OBJECT = Pattern.compile(
            "(?<![\\w$.])(pageContext|pageScope|requestScope|sessionScope|applicationScope|param|paramValues|" +
            "header|headerValues|cookie|initParam)(?![\\w$:])");

    private static final Pattern JAVA_IMPLICIT_OBJECT = Pattern.compile(
            "(?<![\\w$.])(request|response|session|application|out|pageContext|config|page|exception)\\s*\\.");

    private static final Pattern EL_STRING = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"");

    private final Map<String, String> libraryByPrefix = new HashMap<>();
    private final Set<String> tagLibraries = new LinkedHashSet<>();

    /**
     * Counts per construct, in first-seen order, with the construct as written (prefix included).
     */
    final Map<Key, Integer> counts = new LinkedHashMap<>();
    final Map<Key, String> writtenAs = new HashMap<>();

    int scriptlets;
    int javaLines;
    int expressions;
    int declarations;
    int elExpressions;
    int customTags;
    int includes;

    JspInventoryCollector collect(Jsp.Document document) {
        walk(document.getNodes(), true);
        return this;
    }

    List<String> getTagLibraries() {
        return new ArrayList<>(tagLibraries);
    }

    private void walk(List<Jsp.Content> nodes, boolean count) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Directive) {
                visitDirective((Jsp.Directive) node, count);
            } else if (!count) {
                // Inside an included file: only its taglib declarations (and nested includes) matter.
                if (node instanceof Jsp.Tag && ((Jsp.Tag) node).getBody() != null) {
                    walk(((Jsp.Tag) node).getBody(), false);
                }
            } else if (node instanceof Jsp.Scriptlet) {
                scriptlets++;
                add(Category.SCRIPTING, "scriptlet", "", "scriptlet");
                visitJava(((Jsp.Scriptlet) node).getCode(), true);
            } else if (node instanceof Jsp.Declaration) {
                declarations++;
                add(Category.SCRIPTING, "declaration", "", "declaration");
                visitJava(((Jsp.Declaration) node).getCode(), true);
            } else if (node instanceof Jsp.ExpressionScriptlet) {
                expressions++;
                add(Category.SCRIPTING, "expression", "", "expression");
                visitJava(((Jsp.ExpressionScriptlet) node).getCode(), false);
            } else if (node instanceof Jsp.ExpressionLanguage) {
                visitEl(((Jsp.ExpressionLanguage) node).getExpression());
            } else if (node instanceof Jsp.Tag) {
                visitTag((Jsp.Tag) node);
            }
        }
    }

    private void visitDirective(Jsp.Directive directive, boolean count) {
        if (count) {
            add(Category.DIRECTIVE, directive.getName(), "", directive.getName());
            if ("include".equals(directive.getName())) {
                includes++;
            }
        }
        if ("taglib".equals(directive.getName())) {
            String prefix = attribute(directive.getAttributes(), "prefix");
            String uri = attribute(directive.getAttributes(), "uri");
            String tagdir = attribute(directive.getAttributes(), "tagdir");
            String library = uri != null ? uri : tagdir != null ? "tagdir:" + tagdir : null;
            if (prefix != null && library != null) {
                libraryByPrefix.put(prefix, library);
                if (count) {
                    tagLibraries.add(library);
                }
            }
        }
        if (directive.getIncludedFile() != null) {
            walk(directive.getIncludedFile().getNodes(), false);
        }
    }

    private void visitTag(Jsp.Tag tag) {
        String name = tag.getName();
        int colon = name.indexOf(':');
        String prefix = name.substring(0, colon);
        String library = "jsp".equals(prefix) ? STANDARD_ACTION : libraryByPrefix.getOrDefault(prefix, UNDECLARED);
        add(Category.TAG, name.substring(colon + 1), library, name);
        if ("jsp".equals(prefix)) {
            if ("jsp:include".equals(name)) {
                includes++;
            }
        } else {
            customTags++;
        }

        for (Jsp.Attribute attribute : tag.getAttributes()) {
            String value = attribute.getValue().getValue();
            for (String el : JspVariables.elInText(value)) {
                visitEl(el);
            }
            for (String code : JspVariables.expressionsInText(value)) {
                expressions++;
                add(Category.SCRIPTING, "expression in tag attribute", "", "expression in tag attribute");
                visitJava(code, false);
            }
        }
        if (tag.getBody() != null) {
            walk(tag.getBody(), true);
        }
    }

    private void visitJava(String code, boolean countLines) {
        if (countLines) {
            for (String line : code.split("\n", -1)) {
                if (!line.isBlank()) {
                    javaLines++;
                }
            }
        }
        Matcher m = JAVA_IMPLICIT_OBJECT.matcher(JspVariables.maskLiteralsAndComments(code));
        while (m.find()) {
            add(Category.JAVA_IMPLICIT_OBJECT, m.group(1), "", m.group(1));
        }
    }

    private void visitEl(String expression) {
        elExpressions++;
        String masked = EL_STRING.matcher(expression).replaceAll("''");
        Matcher function = EL_FUNCTION_CALL.matcher(masked);
        while (function.find()) {
            String prefix = function.group(1);
            add(Category.EL_FUNCTION, function.group(2), libraryByPrefix.getOrDefault(prefix, UNDECLARED),
                    prefix + ":" + function.group(2));
        }
        Matcher implicit = EL_IMPLICIT_OBJECT.matcher(masked);
        while (implicit.find()) {
            add(Category.EL_IMPLICIT_OBJECT, implicit.group(1), "", implicit.group(1));
        }
    }

    private void add(Category category, String name, String library, String written) {
        Key key = new Key(category, name, library);
        counts.merge(key, 1, Integer::sum);
        writtenAs.putIfAbsent(key, written);
    }

    private static @Nullable String attribute(List<Jsp.Attribute> attributes, String name) {
        for (Jsp.Attribute attribute : attributes) {
            if (name.equals(attribute.getName())) {
                return attribute.getValue().getValue();
            }
        }
        return null;
    }
}
