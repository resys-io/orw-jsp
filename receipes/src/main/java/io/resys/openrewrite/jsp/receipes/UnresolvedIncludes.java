package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.tree.Jsp;
import org.openrewrite.ParseWarning;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects pages whose {@code <%@ include file="..." %>} directives (anywhere on the page, including
 * inside tag bodies and nested inside other included files) could not be resolved by the parser.
 * <p>
 * Recipes that decide whether something on a page is "used" must skip such pages: the content of
 * the missing include is invisible to them, so anything referenced only from it would look unused
 * and be wrongly deleted.
 */
final class UnresolvedIncludes {
    private static final System.Logger LOGGER = System.getLogger(UnresolvedIncludes.class.getName());

    private UnresolvedIncludes() {
    }

    /**
     * @return {@code true} (after logging a warning per unresolved include) if the recipe named
     * {@code recipeName} must leave {@code document} untouched.
     */
    static boolean mustSkip(Jsp.Document document, String recipeName) {
        List<String> warnings = find(document);
        for (String warning : warnings) {
            LOGGER.log(System.Logger.Level.WARNING, "{0}: skipping {1} because a static include is unresolved: {2}",
                    recipeName, document.getSourcePath(), warning);
        }
        return !warnings.isEmpty();
    }

    /**
     * @return a description of each unresolved include on the page, without logging anything.
     */
    static List<String> find(Jsp.Document document) {
        List<String> warnings = new ArrayList<>();
        collect(document.getNodes(), document.getSourcePath(), warnings);
        return warnings;
    }

    private static void collect(List<Jsp.Content> nodes, Path file, List<String> warnings) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Directive) {
                Jsp.Directive directive = (Jsp.Directive) node;
                directive.getMarkers().findFirst(ParseWarning.class)
                        .ifPresent(warning -> warnings.add(warning.getMessage() + " (in " + file + ")"));
                Jsp.IncludedFile includedFile = directive.getIncludedFile();
                if (includedFile != null) {
                    collect(includedFile.getNodes(), includedFile.getSourcePath(), warnings);
                }
            } else if (node instanceof Jsp.Tag) {
                List<Jsp.Content> body = ((Jsp.Tag) node).getBody();
                if (body != null) {
                    collect(body, file, warnings);
                }
            }
        }
    }
}
