package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.JspIsoVisitor;
import io.resys.orw.jsp.tree.Jsp;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes unreferenced and duplicate classes from {@code <%@ page import="..." %>} directives.
 * <p>
 * The {@code import} attribute is the one JSP {@code page} directive attribute that may hold a
 * comma-separated list and may repeat across multiple {@code <%@ page %>} directives on the same
 * page (see Jakarta Server Pages 4.0 §7.3.3). Each comma-separated entry is checked independently:
 * an entry is "used" if its simple class name (the part after the last {@code .}) occurs as a
 * whole word anywhere in the page's Java code - {@code <% %>} scriptlets, {@code <%! %>}
 * declarations, and {@code <%= %>} expression scriptlets, including inside custom tag bodies.
 * Unused entries are dropped from the comma list; if that empties a directive's {@code import}
 * attribute entirely, the attribute is removed, and if {@code import} was that directive's only
 * attribute, the whole directive is removed.
 * <p>
 * Entries are also deduplicated: the first occurrence of a given import (by its exact text, after
 * trimming) on the page is kept (subject to the usual unused check), and every later occurrence -
 * whether a repeat within the same comma list or in a different {@code <%@ page import="..." %>}
 * directive further down the page - is dropped as redundant, regardless of whether it's used.
 * <p>
 * Two things are deliberately conservative, both because getting them wrong would delete an import
 * a page still needs:
 * <ul>
 *     <li>Wildcard imports ({@code import="java.util.*"}) are never removed - there's no reliable
 *     way to tell whether "any class from this package" is used without a real Java type checker,
 *     which this module does not have.</li>
 *     <li>Usage is a whole-word match on the simple class name, not a fully resolved type
 *     reference. This can only produce false positives (an import kept even though it isn't really
 *     used - e.g. an unrelated {@code java.awt.List} reference would count as "using" an imported
 *     {@code java.util.List}), never a false negative that would delete an import the code still
 *     needs.</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveUnusedImports extends Recipe {

    String displayName = "Remove unused JSP `page` imports";

    String description = "Removes unreferenced and duplicate classes from `<%@ page import=\"...\" %>` " +
                          "directives. If removing entries empties a directive's `import` attribute, " +
                          "the attribute is removed; if `import` was that directive's only attribute, " +
                          "the whole directive is removed.";

    private static final String PAGE = "page";
    private static final String IMPORT_ATTRIBUTE = "import";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JspIsoVisitor<ExecutionContext>() {
            @Override
            public Jsp.Document visitDocument(Jsp.Document document, ExecutionContext ctx) {
                if (UnresolvedIncludes.mustSkip(document, RemoveUnusedImports.class.getSimpleName())) {
                    return document;
                }
                String javaCode = collectJavaCode(document.getNodes());
                Set<String> seenImports = new LinkedHashSet<>();
                return document.withNodes(rewriteNodes(document.getNodes(), javaCode, seenImports));
            }
        };
    }

    private static String collectJavaCode(List<Jsp.Content> nodes) {
        StringBuilder sb = new StringBuilder();
        appendJavaCode(nodes, sb);
        return sb.toString();
    }

    private static void appendJavaCode(List<Jsp.Content> nodes, StringBuilder sb) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Scriptlet) {
                sb.append(((Jsp.Scriptlet) node).getCode()).append('\n');
            } else if (node instanceof Jsp.Declaration) {
                sb.append(((Jsp.Declaration) node).getCode()).append('\n');
            } else if (node instanceof Jsp.ExpressionScriptlet) {
                sb.append(((Jsp.ExpressionScriptlet) node).getCode()).append('\n');
            } else if (node instanceof Jsp.Tag) {
                List<Jsp.Content> body = ((Jsp.Tag) node).getBody();
                if (body != null) {
                    appendJavaCode(body, sb);
                }
            } else if (node instanceof Jsp.Directive) {
                Jsp.IncludedFile includedFile = ((Jsp.Directive) node).getIncludedFile();
                if (includedFile != null) {
                    // A statically included fragment is compiled into the same servlet as the page.
                    appendJavaCode(includedFile.getNodes(), sb);
                }
            }
        }
    }

    private static List<Jsp.Content> rewriteNodes(List<Jsp.Content> nodes, String javaCode, Set<String> seenImports) {
        return ListUtils.map(nodes, node -> {
            if (node instanceof Jsp.Directive) {
                Jsp.Directive directive = (Jsp.Directive) node;
                if (PAGE.equals(directive.getName())) {
                    return rewritePageDirective(directive, javaCode, seenImports);
                }
                return node;
            }
            if (node instanceof Jsp.Tag) {
                Jsp.Tag tag = (Jsp.Tag) node;
                if (tag.getBody() != null) {
                    return tag.withBody(rewriteNodes(tag.getBody(), javaCode, seenImports));
                }
            }
            return node;
        });
    }

    /**
     * @return the directive with unused/duplicate entries dropped from its {@code import} attribute,
     * the directive with the {@code import} attribute removed entirely (if every entry was dropped
     * and other attributes remain), or {@code null} (if every entry was dropped and {@code import}
     * was the only attribute - i.e. remove the whole directive).
     */
    private static Jsp.@Nullable Directive rewritePageDirective(Jsp.Directive directive, String javaCode,
                                                                  Set<String> seenImports) {
        List<Jsp.Attribute> attributes = directive.getAttributes();
        int importIndex = -1;
        for (int i = 0; i < attributes.size(); i++) {
            if (IMPORT_ATTRIBUTE.equals(attributes.get(i).getName())) {
                importIndex = i;
                break;
            }
        }
        if (importIndex < 0) {
            return directive;
        }

        Jsp.Attribute importAttribute = attributes.get(importIndex);
        List<String> entries = splitImports(importAttribute.getValue().getValue());
        List<String> kept = new ArrayList<>(entries.size());
        for (String entry : entries) {
            String className = entry.trim();
            if (className.isEmpty() || seenImports.contains(className)) {
                // Empty (e.g. a stray trailing comma), or a repeat of an import already kept
                // earlier on the page - either way, redundant.
                continue;
            }
            if (isUsed(className, javaCode)) {
                seenImports.add(className);
                kept.add(entry);
            }
        }

        if (kept.size() == entries.size()) {
            return directive;
        }
        if (kept.isEmpty()) {
            if (attributes.size() == 1) {
                return null;
            }
            Jsp.Attribute toRemove = importAttribute;
            return directive.withAttributes(ListUtils.map(attributes, a -> a == toRemove ? null : a));
        }

        Jsp.Attribute newImportAttribute = importAttribute.withValue(
                importAttribute.getValue().withValue(String.join(",", kept)));
        List<Jsp.Attribute> newAttributes = new ArrayList<>(attributes);
        newAttributes.set(importIndex, newImportAttribute);
        return directive.withAttributes(newAttributes);
    }

    /**
     * Splits a comma-separated {@code import} attribute value into its entries, preserving each
     * entry's own surrounding whitespace so re-joining any surviving subset with {@code ","}
     * reproduces the original spacing exactly.
     */
    private static List<String> splitImports(String value) {
        List<String> entries = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == ',') {
                entries.add(value.substring(start, i));
                start = i + 1;
            }
        }
        entries.add(value.substring(start));
        return entries;
    }

    private static boolean isUsed(String className, String javaCode) {
        if (className.endsWith(".*")) {
            // Can't safely tell whether a wildcard import is used without a real type checker;
            // always keep it rather than risk deleting something the page still needs.
            return true;
        }
        int lastDot = className.lastIndexOf('.');
        String simpleName = lastDot < 0 ? className : className.substring(lastDot + 1);
        if (simpleName.isEmpty()) {
            return true;
        }
        return Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b").matcher(javaCode).find();
    }
}
