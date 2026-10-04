package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.JspIsoVisitor;
import io.resys.orw.jsp.tree.Jsp;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Removes {@code <%@ taglib prefix="..." ... %>} directives whose declared prefix is never
 * referenced anywhere else on the page.
 * <p>
 * A prefix counts as "used" if it appears either:
 * <ul>
 *     <li>as the namespace of a standard/custom action, e.g. {@code c} for {@code <c:if>}; or</li>
 *     <li>as an EL function reference, e.g. {@code fn} for {@code ${fn:length(list)}} - this matters
 *     because function-only tag libraries (like the JSTL functions library) are never used as
 *     elements at all, only from EL.</li>
 * </ul>
 * The usage scan looks for a raw {@code prefix:} occurrence (not preceded by another identifier
 * character) inside any EL expression or quoted attribute value on the page, rather than requiring
 * it to sit in a fully-parsed EL function call. That's deliberately permissive: it can only produce
 * false positives (a taglib kept even though it isn't really used), never a false negative that
 * would delete a taglib a page still needs.
 * <p>
 * Removing a directive does not clean up the (now possibly blank) line it stood on - the
 * surrounding template text is left untouched, exactly as {@link Jsp.Text} content elsewhere on
 * the page would be.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveUnusedTaglibs extends Recipe {

    String displayName = "Remove unused JSP `taglib` directives";

    String description = "Removes `<%@ taglib prefix=\"...\" ... %>` directives whose declared prefix " +
                          "is never referenced elsewhere on the page, either as a custom tag " +
                          "(`<prefix:tag>`) or as an EL function reference (`${prefix:function(...)}`).";

    private static final String TAGLIB = "taglib";
    private static final String PREFIX_ATTRIBUTE = "prefix";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JspIsoVisitor<ExecutionContext>() {
            @Override
            public Jsp.Document visitDocument(Jsp.Document document, ExecutionContext ctx) {
                if (UnresolvedIncludes.mustSkip(document, RemoveUnusedTaglibs.class.getSimpleName())) {
                    return document;
                }
                Set<String> unusedPrefixes = findUnusedTaglibPrefixes(document);
                if (unusedPrefixes.isEmpty()) {
                    return document;
                }
                return document.withNodes(removeTaglibDirectives(document.getNodes(), unusedPrefixes));
            }
        };
    }

    private static Set<String> findUnusedTaglibPrefixes(Jsp.Document document) {
        Set<String> declared = new LinkedHashSet<>();
        collectDeclaredPrefixes(document.getNodes(), declared);
        if (declared.isEmpty()) {
            return Collections.emptySet();
        }

        Map<String, Pattern> referencePatterns = new LinkedHashMap<>();
        for (String prefix : declared) {
            // A prefix reference is the prefix immediately followed by ':', not preceded by another
            // identifier character (so prefix "c" doesn't spuriously match inside "abc:whatever").
            referencePatterns.put(prefix, Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(prefix) + ":"));
        }

        Set<String> used = new LinkedHashSet<>();
        collectUsedPrefixes(document.getNodes(), referencePatterns, used);

        Set<String> unused = new LinkedHashSet<>(declared);
        unused.removeAll(used);
        return unused;
    }

    private static void collectDeclaredPrefixes(List<Jsp.Content> nodes, Set<String> declared) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Directive) {
                Jsp.Directive directive = (Jsp.Directive) node;
                if (TAGLIB.equals(directive.getName())) {
                    findAttributeValue(directive.getAttributes(), PREFIX_ATTRIBUTE).ifPresent(declared::add);
                }
            } else if (node instanceof Jsp.Tag) {
                List<Jsp.Content> body = ((Jsp.Tag) node).getBody();
                if (body != null) {
                    collectDeclaredPrefixes(body, declared);
                }
            }
        }
    }

    private static void collectUsedPrefixes(List<Jsp.Content> nodes, Map<String, Pattern> referencePatterns,
                                             Set<String> used) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Tag) {
                Jsp.Tag tag = (Jsp.Tag) node;
                markTagNameUsage(tag.getName(), referencePatterns, used);
                for (Jsp.Attribute attribute : tag.getAttributes()) {
                    markTextUsage(attribute.getValue().getValue(), referencePatterns, used);
                }
                if (tag.getBody() != null) {
                    collectUsedPrefixes(tag.getBody(), referencePatterns, used);
                }
            } else if (node instanceof Jsp.ExpressionLanguage) {
                markTextUsage(((Jsp.ExpressionLanguage) node).getExpression(), referencePatterns, used);
            } else if (node instanceof Jsp.Directive) {
                Jsp.Directive directive = (Jsp.Directive) node;
                for (Jsp.Attribute attribute : directive.getAttributes()) {
                    markTextUsage(attribute.getValue().getValue(), referencePatterns, used);
                }
                if (directive.getIncludedFile() != null) {
                    // A statically included fragment shares the page's taglib declarations.
                    collectUsedPrefixes(directive.getIncludedFile().getNodes(), referencePatterns, used);
                }
            }
        }
    }

    private static void markTagNameUsage(String tagName, Map<String, Pattern> referencePatterns, Set<String> used) {
        int colon = tagName.indexOf(':');
        if (colon > 0) {
            String prefix = tagName.substring(0, colon);
            if (referencePatterns.containsKey(prefix)) {
                used.add(prefix);
            }
        }
    }

    private static void markTextUsage(String text, Map<String, Pattern> referencePatterns, Set<String> used) {
        if (text.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Pattern> entry : referencePatterns.entrySet()) {
            String prefix = entry.getKey();
            if (!used.contains(prefix) && entry.getValue().matcher(text).find()) {
                used.add(prefix);
            }
        }
    }

    private static List<Jsp.Content> removeTaglibDirectives(List<Jsp.Content> nodes, Set<String> unusedPrefixes) {
        return ListUtils.map(nodes, node -> {
            if (node instanceof Jsp.Directive) {
                Jsp.Directive directive = (Jsp.Directive) node;
                if (TAGLIB.equals(directive.getName())) {
                    Optional<String> prefix = findAttributeValue(directive.getAttributes(), PREFIX_ATTRIBUTE);
                    if (prefix.isPresent() && unusedPrefixes.contains(prefix.get())) {
                        return null;
                    }
                }
                return node;
            }
            if (node instanceof Jsp.Tag) {
                Jsp.Tag tag = (Jsp.Tag) node;
                if (tag.getBody() != null) {
                    return tag.withBody(removeTaglibDirectives(tag.getBody(), unusedPrefixes));
                }
            }
            return node;
        });
    }

    private static Optional<String> findAttributeValue(List<Jsp.Attribute> attributes, String name) {
        for (Jsp.Attribute attribute : attributes) {
            if (name.equals(attribute.getName())) {
                return Optional.of(attribute.getValue().getValue());
            }
        }
        return Optional.empty();
    }
}
