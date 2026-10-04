package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.internal.JspPrinter;
import io.resys.openrewrite.jsp.tree.Jsp;
import org.jspecify.annotations.Nullable;
import org.openrewrite.PrintOutputCapture;
import org.openrewrite.Tree;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Where each node of a {@link Jsp.Document} starts in its printed source, for reporting line
 * numbers. Content of statically included files is not printed with the page, so its nodes have
 * no position here.
 */
final class JspPositions {

    private final String source;
    private final Map<UUID, Integer> offsets;

    private JspPositions(String source, Map<UUID, Integer> offsets) {
        this.source = source;
        this.offsets = offsets;
    }

    static JspPositions of(Jsp.Document document) {
        Map<UUID, Integer> offsets = new HashMap<>();
        PrintOutputCapture<Integer> out = new PrintOutputCapture<>(0);
        new JspPrinter<Integer>() {
            @Override
            public @Nullable Jsp visit(@Nullable Tree tree, PrintOutputCapture<Integer> p) {
                if (tree != null) {
                    offsets.putIfAbsent(tree.getId(), p.getOut().length());
                }
                return super.visit(tree, p);
            }
        }.visit(document, out);
        return new JspPositions(out.getOut(), offsets);
    }

    /**
     * @return the 1-based line on which the node starts (plus {@code newlinesAfterStart}), or
     * {@code -1} if the node isn't part of the printed page.
     */
    int line(Tree node, int newlinesAfterStart) {
        Integer offset = offsets.get(node.getId());
        if (offset == null) {
            return -1;
        }
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line + newlinesAfterStart;
    }

    static int newlines(String text, int end) {
        int count = 0;
        for (int i = 0; i < end && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }
}
