package io.resys.orw.jsp;

import io.resys.orw.jsp.tree.Jsp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ParseWarning;
import org.openrewrite.test.RewriteTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static io.resys.orw.jsp.Assertions.jsp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.test.RewriteTest.toRecipe;

/**
 * Static includes ({@code <%@ include file="..." %>}): the included file's content is embedded
 * read-only into the including page's tree.
 */
class JspIncludeTest implements RewriteTest {

    @Test
    void embedsIncludedFileContent() {
        rewriteRun(
                jsp(
                        """
                        <%@ include file="includes/page_name.jsp" %>
                        <html></html>
                        """,
                        spec -> spec.path("index.jsp").afterRecipe(document -> {
                            Jsp.IncludedFile included = includeDirective(document).getIncludedFile();
                            assertThat(included).isNotNull();
                            assertThat(included.getSourcePath()).isEqualTo(Path.of("includes/page_name.jsp"));
                            assertThat(included.getNodes()).hasSize(2);
                            assertThat(included.getNodes().get(0)).isInstanceOf(Jsp.Text.class);
                            assertThat(((Jsp.ExpressionLanguage) included.getNodes().get(1)).getExpression())
                                    .isEqualTo("pageName");
                        })
                ),
                jsp("<h1>${pageName}", spec -> spec.path("includes/page_name.jsp"))
        );
    }

    @Test
    void nestedIncludesResolveRelativeToTheIncludingFile() {
        rewriteRun(
                jsp(
                        "<%@ include file=\"includes/outer.jspf\" %>",
                        spec -> spec.path("index.jsp").afterRecipe(document -> {
                            Jsp.IncludedFile outer = includeDirective(document).getIncludedFile();
                            assertThat(outer).isNotNull();
                            Jsp.IncludedFile inner = ((Jsp.Directive) outer.getNodes().get(0)).getIncludedFile();
                            assertThat(inner).isNotNull();
                            assertThat(inner.getSourcePath()).isEqualTo(Path.of("includes/inner.jspf"));
                        })
                ),
                jsp("<%@ include file=\"inner.jspf\" %>", spec -> spec.path("includes/outer.jspf")),
                jsp("inner", spec -> spec.path("includes/inner.jspf"))
        );
    }

    @Test
    void contextRelativeIncludeResolvesAgainstWebRoot() {
        rewriteRun(
                jsp(
                        "<%@ include file=\"/WEB-INF/jspf/header.jspf\" %>",
                        spec -> spec.path("src/main/webapp/admin/index.jsp").afterRecipe(document -> {
                            Jsp.IncludedFile included = includeDirective(document).getIncludedFile();
                            assertThat(included).isNotNull();
                            assertThat(included.getSourcePath())
                                    .isEqualTo(Path.of("src/main/webapp/WEB-INF/jspf/header.jspf"));
                        })
                ),
                jsp("header", spec -> spec.path("src/main/webapp/WEB-INF/jspf/header.jspf"))
        );
    }

    @Test
    void unresolvableIncludeIsLeftEmptyWithWarning() {
        rewriteRun(
                jsp(
                        "<%@ include file=\"does/not/exist.jsp\" %>",
                        spec -> spec.path("index.jsp").afterRecipe(document -> {
                            Jsp.Directive include = includeDirective(document);
                            assertThat(include.getIncludedFile()).isNull();
                            assertThat(include.getMarkers().findFirst(ParseWarning.class))
                                    .hasValueSatisfying(w -> assertThat(w.getMessage())
                                            .isEqualTo("Included file 'does/not/exist.jsp' not found"));
                        })
                )
        );
    }

    @Test
    void unparseableIncludeIsLeftEmptyWithWarning(@TempDir Path dir) throws IOException {
        // On disk rather than a sibling source, which would itself (correctly) fail to parse.
        Files.writeString(dir.resolve("broken.jspf"), "<% unterminated");
        rewriteRun(
                jsp(
                        "<%@ include file=\"broken.jspf\" %>",
                        spec -> spec.path(dir.resolve("index.jsp")).afterRecipe(document -> {
                            Jsp.Directive include = includeDirective(document);
                            assertThat(include.getIncludedFile()).isNull();
                            assertThat(include.getMarkers().findFirst(ParseWarning.class))
                                    .hasValueSatisfying(w -> assertThat(w.getMessage())
                                            .startsWith("Included file 'broken.jspf' could not be parsed"));
                        })
                )
        );
    }

    @Test
    void includeIsResolvedFromDisk(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("includes"));
        Files.writeString(dir.resolve("includes/page_name.jsp"), "${pageName}");
        rewriteRun(
                jsp(
                        "<%@ include file=\"includes/page_name.jsp\" %>",
                        spec -> spec.path(dir.resolve("index.jsp")).afterRecipe(document ->
                                assertThat(includeDirective(document).getIncludedFile()).isNotNull())
                )
        );
    }

    @Test
    void recursiveIncludeIsNotFollowedForever() {
        rewriteRun(
                jsp(
                        "<%@ include file=\"b.jsp\" %>",
                        spec -> spec.path("a.jsp").afterRecipe(document -> {
                            Jsp.IncludedFile b = includeDirective(document).getIncludedFile();
                            assertThat(b).isNotNull();
                            Jsp.Directive backToA = (Jsp.Directive) b.getNodes().get(0);
                            assertThat(backToA.getIncludedFile()).isNull();
                            assertThat(backToA.getMarkers().findFirst(ParseWarning.class)).isEmpty();
                        })
                ),
                jsp("<%@ include file=\"a.jsp\" %>", spec -> spec.path("b.jsp"))
        );
    }

    @Test
    void visitorSeesIncludedContentButCannotChangeIt() {
        rewriteRun(
                spec -> spec.recipe(toRecipe(() -> new JspIsoVisitor<>() {
                    @Override
                    public Jsp.Text visitText(Jsp.Text text, ExecutionContext ctx) {
                        // Only rewrite text reached through an include, never the included file itself.
                        if (getCursor().firstEnclosing(Jsp.IncludedFile.class) != null) {
                            return text.withText("changed");
                        }
                        return text;
                    }
                })),
                jsp("<%@ include file=\"fragment.jspf\" %>", spec -> spec.path("index.jsp")),
                jsp("original", spec -> spec.path("fragment.jspf"))
        );
    }

    @Test
    void visitorReachesIncludedContent() {
        rewriteRun(
                jsp(
                        "<%@ include file=\"fragment.jspf\" %>",
                        spec -> spec.path("index.jsp").afterRecipe(document -> {
                            List<String> texts = new JspIsoVisitor<List<String>>() {
                                @Override
                                public Jsp.Text visitText(Jsp.Text text, List<String> acc) {
                                    acc.add(text.getText());
                                    return text;
                                }
                            }.reduce(document, new ArrayList<>());
                            assertThat(texts).containsExactly("fragment");
                        })
                ),
                jsp("fragment", spec -> spec.path("fragment.jspf"))
        );
    }

    private static Jsp.Directive includeDirective(Jsp.Document document) {
        List<Jsp.Directive> includes = document.getNodes().stream()
                .filter(Jsp.Directive.class::isInstance)
                .map(Jsp.Directive.class::cast)
                .filter(d -> "include".equals(d.getName()))
                .collect(Collectors.toList());
        assertThat(includes).hasSize(1);
        return includes.get(0);
    }
}
