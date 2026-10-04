package io.resys.orw.jsp.receipes;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.resys.orw.jsp.receipes.table.JspFixtureChanges;
import io.resys.orw.jsp.tree.Jsp;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.text.PlainText;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Maintains the tester's fixtures (see {@code io.resys.orw.jsp.tester.Fixtures}) for the JSP pages,
 * from the inputs {@link FindModelAttributes} finds each page reads:
 * <ul>
 *     <li>a page with no fixture (none whose {@code "page"} is it) gets a skeleton: its request
 *     parameters, and its request, session, and application attributes, each shaped by its type and
 *     the properties the page reads ({@code {"@class": "com.acme.Order", "customer": {"name": ""}}}),
 *     with placeholder values ({@code ""}, {@code 0}, {@code false}) to fill in. Inputs a fixture
 *     can't express go in a {@code "_todo"} list, which the tester ignores;</li>
 *     <li>each existing fixture of a page gets the inputs it lacks added - sections, attributes,
 *     nested properties (also in each element of a list) - never changing or removing what it has,
 *     nor adding {@code "@class"} to an object written without it. A fixture with everything is left
 *     as it is;</li>
 *     <li>with {@link #getDeleteOrphans()}, a fixture whose page no longer exists is deleted, with its
 *     expected output.</li>
 * </ul>
 * {@link #getIncludes()} and {@link #getExcludes()} (patterns on the page path, e.g.
 * {@code /WEB-INF/views/**}) choose the pages: only they get fixtures created and updated, and only
 * their fixtures can be deleted.
 * <p>
 * The pages ({@code .jsp}/{@code .jspf} under {@link #getWebappDirectory()}) must be parsed with
 * {@code JspParser}, and the fixture files ({@code .json} and {@code .expected.html} under
 * {@link #getFixturesDirectory()}) as plain text. A page counts as existing if it was parsed, so
 * parse them all. A {@code struts-config.xml} parsed along with them gives form beans' types.
 * Every change is listed in the {@link JspFixtureChanges} data table.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MaintainFixtures extends ScanningRecipe<MaintainFixtures.Accumulator> {

    transient JspFixtureChanges table = new JspFixtureChanges(this);

    String displayName = "Maintain tester fixtures for JSP pages";

    String description = "Creates a skeleton tester fixture for each JSP page without one, from the inputs the page " +
                          "reads; adds inputs a page now reads to its existing fixtures; and, if asked, deletes " +
                          "fixtures whose page no longer exists.";

    @Option(displayName = "Fixtures directory",
            description = "Where the fixtures are, relative to the project root. Defaults to `src/test/fixtures`.",
            example = "src/test/fixtures",
            required = false)
    @Nullable
    String fixturesDirectory;

    @Option(displayName = "Web application directory",
            description = "The web application's root, relative to the project root; page paths are relative " +
                          "to it. Defaults to `src/main/webapp`.",
            example = "src/main/webapp",
            required = false)
    @Nullable
    String webappDirectory;

    @Option(displayName = "Includes",
            description = "Pages to maintain fixtures for, as patterns on the page path (e.g. " +
                          "`/WEB-INF/views/orders.jsp`): `*` matches within a path segment, `**` across segments, " +
                          "`?` one character. Defaults to `**/*.jsp` (every page, but not .jspf fragments).",
            example = "/WEB-INF/views/**/*.jsp",
            required = false)
    @Nullable
    List<String> includes;

    @Option(displayName = "Excludes",
            description = "Pages not to maintain fixtures for, as for includes: they get none created or updated, " +
                          "and theirs are never deleted.",
            example = "/WEB-INF/views/admin/**",
            required = false)
    @Nullable
    List<String> excludes;

    @Option(displayName = "Delete orphans",
            description = "Delete fixtures (and their expected output) whose page no longer exists. Defaults to `false`.",
            required = false)
    @Nullable
    Boolean deleteOrphans;

    public static class Accumulator {
        final StrutsConfig struts = new StrutsConfig();
        /** Every parsed page, by page path. */
        final Map<String, Jsp.Document> pages = new LinkedHashMap<>();
        /** Every fixture (by path), with its page, or null if it isn't valid fixture JSON. */
        final Map<String, String> fixtures = new LinkedHashMap<>();
        final Map<String, FixtureSkeletons.Skeleton> skeletons = new HashMap<>();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    private String fixturesDir() {
        return trimSlashes(fixturesDirectory == null ? "src/test/fixtures" : fixturesDirectory);
    }

    private String webappDir() {
        return trimSlashes(webappDirectory == null ? "src/main/webapp" : webappDirectory);
    }

    private PathGlob scope() {
        return new PathGlob(includes == null || includes.isEmpty() ? List.of("**/*.jsp") : includes,
                excludes == null ? List.of() : excludes);
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof Jsp.Document) {
                    String page = pagePath(((Jsp.Document) tree).getSourcePath());
                    if (page != null) {
                        acc.pages.put(page, (Jsp.Document) tree);
                    }
                } else if (tree instanceof Xml.Document && StrutsConfig.isStrutsConfig((Xml.Document) tree)) {
                    acc.struts.add((Xml.Document) tree);
                } else if (tree instanceof PlainText && isFixture(((PlainText) tree).getSourcePath())) {
                    ObjectNode fixture = FixtureSkeletons.read(((PlainText) tree).getText());
                    String page = fixture == null || !fixture.hasNonNull("page") ? null : fixture.get("page").asText();
                    acc.fixtures.put(path(((PlainText) tree).getSourcePath()), page);
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        PathGlob scope = scope();
        Set<String> pagesWithFixtures = new HashSet<>(acc.fixtures.values());
        Set<String> taken = new HashSet<>(acc.fixtures.keySet());
        List<SourceFile> created = new ArrayList<>();
        for (String page : acc.pages.keySet()) {
            if (!scope.matches(page) || pagesWithFixtures.contains(page)) {
                continue;
            }
            FixtureSkeletons.Skeleton skeleton = skeleton(acc, page);
            String path = fixturePathFor(page, taken);
            taken.add(path);
            // The edit phase visits generated files too: it must know this one is a fixture.
            acc.fixtures.put(path, page);
            created.add(PlainText.builder().sourcePath(Paths.get(path)).text(FixtureSkeletons.write(skeleton.json())).build());
            table.insertRow(ctx, new JspFixtureChanges.Row(path, page, "CREATED",
                    skeleton.todos().isEmpty() ? "" : "to do: " + String.join("; ", skeleton.todos())));
        }
        return created;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        PathGlob scope = scope();
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof PlainText)) {
                    return tree;
                }
                PlainText text = (PlainText) tree;
                String path = path(text.getSourcePath());
                if (path.endsWith(".expected.html") && path.startsWith(fixturesDir() + "/")) {
                    String fixture = path.substring(0, path.length() - ".expected.html".length()) + ".json";
                    return acc.fixtures.containsKey(fixture) && orphanToDelete(acc, scope, acc.fixtures.get(fixture)) ?
                            null : tree;
                }
                if (!isFixture(text.getSourcePath())) {
                    return tree;
                }
                String page = acc.fixtures.get(path);
                if (page == null) {
                    table.insertRow(ctx, new JspFixtureChanges.Row(path, "", "SKIPPED",
                            "not fixture JSON with a \"page\""));
                    return tree;
                }
                if (orphanToDelete(acc, scope, page)) {
                    table.insertRow(ctx, new JspFixtureChanges.Row(path, page, "DELETED", "the page no longer exists"));
                    return null;
                }
                if (!scope.matches(page) || !acc.pages.containsKey(page)) {
                    return tree;
                }
                ObjectNode fixture = FixtureSkeletons.read(text.getText());
                if (fixture == null) {
                    return tree;
                }
                List<String> added = FixtureSkeletons.addMissing(fixture, skeleton(acc, page).json().deepCopy());
                if (added.isEmpty()) {
                    return tree;
                }
                table.insertRow(ctx, new JspFixtureChanges.Row(path, page, "UPDATED", "added " + String.join(", ", added)));
                return text.withText(FixtureSkeletons.write(fixture));
            }
        };
    }

    private boolean orphanToDelete(Accumulator acc, PathGlob scope, @Nullable String page) {
        return Boolean.TRUE.equals(deleteOrphans) && page != null && scope.matches(page) && !acc.pages.containsKey(page);
    }

    private FixtureSkeletons.Skeleton skeleton(Accumulator acc, String page) {
        return acc.skeletons.computeIfAbsent(page, p -> {
            Jsp.Document document = acc.pages.get(p);
            return FixtureSkeletons.skeleton(p, new ModelAttributeCollector(document.getSourcePath(), acc.struts)
                    .collect(document));
        });
    }

    /**
     * {@code src/main/webapp/WEB-INF/views/orders.jsp} to {@code /WEB-INF/views/orders.jsp}, or null
     * outside the web application directory.
     */
    private @Nullable String pagePath(Path sourcePath) {
        String path = path(sourcePath);
        String root = webappDir() + "/";
        return path.startsWith(root) ? "/" + path.substring(root.length()) : null;
    }

    private boolean isFixture(Path sourcePath) {
        String path = path(sourcePath);
        return path.startsWith(fixturesDir() + "/") && path.endsWith(".json");
    }

    /**
     * A new fixture's path: the page path without a leading {@code /WEB-INF/views/} (etc.) and its
     * extension, e.g. {@code src/test/fixtures/orders/list.json}, numbered if taken.
     */
    private String fixturePathFor(String page, Set<String> taken) {
        String name = page.substring(1);
        for (String prefix : List.of("WEB-INF/views/", "WEB-INF/jsp/", "WEB-INF/pages/", "WEB-INF/")) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
                break;
            }
        }
        name = name.replaceAll("\\.jspf?$", "");
        String path = fixturesDir() + "/" + name + ".json";
        for (int n = 2; taken.contains(path); n++) {
            path = fixturesDir() + "/" + name + "-" + n + ".json";
        }
        return path;
    }

    private static String path(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String trimSlashes(String directory) {
        String d = directory.replace('\\', '/');
        while (d.endsWith("/")) {
            d = d.substring(0, d.length() - 1);
        }
        return d.startsWith("./") ? d.substring(2) : d;
    }
}
