package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.JspIsoVisitor;
import io.resys.openrewrite.jsp.receipes.table.JspConstructTotals;
import io.resys.openrewrite.jsp.receipes.table.JspConstructUsage;
import io.resys.openrewrite.jsp.receipes.table.JspPageInventory;
import io.resys.openrewrite.jsp.tree.Jsp;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes an inventory of the JSP constructs used across all pages, to plan a migration: which tags
 * (by library), EL functions, implicit objects, directives, and scripting elements the pages use,
 * how often, and on how many pages; plus each page's size and composition. Changes nothing; the
 * results are three data tables:
 * <ul>
 *     <li>{@link JspConstructTotals} - each construct across all pages, most used first;</li>
 *     <li>{@link JspConstructUsage} - each construct per page;</li>
 *     <li>{@link JspPageInventory} - per-page metrics (lines, scriptlets and their Java lines,
 *     expressions, declarations, EL, custom tags, includes, tag libraries).</li>
 * </ul>
 * Tags and EL functions are attributed to their tag library by the {@code uri} (or {@code tagdir})
 * their prefix is declared with, including declarations in statically included files, so the same
 * library used under different prefixes on different pages is counted together. Each file's own
 * content is counted once, for that file: included files are not counted again as part of the
 * pages including them.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class JspInventory extends ScanningRecipe<JspInventory.Accumulator> {

    transient JspConstructTotals totals = new JspConstructTotals(this);
    transient JspConstructUsage usage = new JspConstructUsage(this);
    transient JspPageInventory pages = new JspPageInventory(this);

    String displayName = "JSP inventory";

    String description = "Lists which JSP tags (by tag library), EL functions, implicit objects, directives, and " +
                          "scripting elements the pages use, how often and on how many pages, plus the size and " +
                          "composition of each page, to plan a migration. Changes nothing.";

    public static class Accumulator {
        final Map<JspInventoryCollector.Key, Total> totals = new LinkedHashMap<>();
    }

    static final class Total {
        final String writtenAs;
        int count;
        int pages;

        Total(String writtenAs) {
            this.writtenAs = writtenAs;
        }
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JspIsoVisitor<ExecutionContext>() {
            @Override
            public Jsp.Document visitDocument(Jsp.Document document, ExecutionContext ctx) {
                JspInventoryCollector page = new JspInventoryCollector().collect(document);
                String path = document.getSourcePath().toString();

                page.counts.forEach((key, count) -> {
                    usage.insertRow(ctx, new JspConstructUsage.Row(path, key.category().name(),
                            page.writtenAs.get(key), key.library(), count));
                    Total total = acc.totals.computeIfAbsent(key, k -> new Total(page.writtenAs.get(key)));
                    total.count += count;
                    total.pages++;
                });
                pages.insertRow(ctx, new JspPageInventory.Row(path, lines(document.printAll()), page.scriptlets,
                        page.javaLines, page.expressions, page.declarations, page.elExpressions, page.customTags,
                        page.includes, String.join(", ", page.getTagLibraries())));
                return document;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        List<Map.Entry<JspInventoryCollector.Key, Total>> sorted = new ArrayList<>(acc.totals.entrySet());
        sorted.sort(Comparator.comparingInt((Map.Entry<JspInventoryCollector.Key, Total> e) -> e.getValue().count)
                .reversed());
        for (Map.Entry<JspInventoryCollector.Key, Total> entry : sorted) {
            JspInventoryCollector.Key key = entry.getKey();
            Total total = entry.getValue();
            totals.insertRow(ctx, new JspConstructTotals.Row(key.category().name(), total.writtenAs, key.library(),
                    total.count, total.pages));
        }
        return List.of();
    }

    private static int lines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
