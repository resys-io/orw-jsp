# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An [OpenRewrite](https://docs.openrewrite.org/) language module for JSP: a Lossless Semantic Tree
(LST) parser, printer, and visitor infrastructure for the "standard syntax" defined by
[Jakarta Server Pages 4.0](https://jakarta.ee/specifications/pages/4.0/jakarta-server-pages-spec-4.0)
(`<% %>`-style constructs), plus a growing set of recipes built on top of it. This lets OpenRewrite
recipes read, search, and rewrite `.jsp`/`.jspf` files the same way `rewrite-java`, `rewrite-xml`,
etc. do for their languages.

Root coordinates: `io.resys.orw:resys-orw-jsp:1.0-SNAPSHOT` (a `pom`-packaged
reactor, no code of its own). Java 21, built against OpenRewrite 8.90.4 (`rewrite-bom`) and JUnit
6.1.3 (`junit-bom`), both pinned in the root `pom.xml` and inherited by every module.

**Note on spelling:** the recipes module and its package are spelled `receipes` (not `recipes`)
throughout — `artifactId=resys-orw-jsp-receipes`, package
`io.resys.orw.jsp.receipes`. This is a pre-existing typo baked into the directory layout,
artifactId, and package name consistently; match it exactly in new code rather than "fixing" only
part of it (which would just create an inconsistent mix of both spellings).

## Modules

- **`parser`** (`resys-orw-jsp-parser`) — the LST itself: `Jsp` tree model, `JspParser`,
  `JspVisitor`/`JspIsoVisitor`, `JspPrinter`, and the `Assertions.jsp(...)` test helper. See
  **Parser architecture** below.
- **`receipes`** (`resys-orw-jsp-receipes`) — `Recipe` subclasses built on the parser, e.g.
  `RemoveUnusedTaglibs`. Depends on `parser`. See **Recipes** below.

Both modules follow the same dependency shape: `lombok` and `org.jetbrains:annotations` as
`provided`; `rewrite-test` as `provided` (not `test`) because each module's `Assertions` class lives
in `src/main` and is meant to be reused by consumers' own tests; `junit-jupiter` as `test`.

## Build

Maven, toolchain-free (no wrapper is checked in — `.mvn/` is empty). Requires JDK 21. Run from the
repo root; Maven resolves the multi-module reactor automatically.

```sh
mvn clean install                                            # build + test every module, in order
mvn -pl receipes -am test                                     # test receipes, building parser first
mvn -pl parser test -Dtest=JspParserTest#pageDirective         # single test method in one module
```

Lombok (`@Value`/`@With`/`@EqualsAndHashCode`) generates boilerplate at compile time via annotation
processing — no extra Maven config needed, but an IDE must have its Lombok plugin enabled to
resolve the generated accessors/`with*` methods. The root `lombok.config` sets
`lombok.anyConstructor.addConstructorProperties = true`. `RewriteTest` round-trips every recipe
through Jackson, and a `@Value` recipe with `@Option` fields (e.g. `FindJspProblems`) can only be
deserialized through its generated constructor if that constructor carries `@ConstructorProperties`.
Maven doesn't track `lombok.config`, so after changing it run `mvn clean` or nothing is
regenerated.

`org.jspecify.annotations.Nullable` is `TYPE_USE`-only, which bites on any method returning a
*nested* `Jsp` type (`Jsp.Directive`, `Jsp.Tag`, `Jsp.Attribute.Value`, ...): `@Nullable` has to sit
between the outer and inner name, e.g. `Jsp.@Nullable Directive foo(...)`, not
`@Nullable Jsp.Directive foo(...)` or `private static @Nullable Jsp.Directive` — both of the latter
fail with `scoping construct cannot be annotated with type-use annotation`, and (because that error
aborts annotation processing for the whole compilation unit) surface as a spurious
`does not override abstract method getDescription()` on unrelated Lombok-based `Recipe` classes in
the same module. See `org.openrewrite.properties.PropertiesParser#extractContent` upstream for the
same pattern (`Properties.@Nullable Content`), and `RemoveUnusedImports#rewritePageDirective` here.

## Parser architecture (`parser` module)

The design deliberately mirrors `org.openrewrite.properties` (a small, hand-written, non-ANTLR
OpenRewrite language module) rather than `rewrite-xml`/`rewrite-toml` (which use generated ANTLR
grammars) — JSP's syntax is regular enough that a hand-written recursive-descent scanner is simpler
than standing up a grammar. If you're unfamiliar with how OpenRewrite language modules fit
together, reading `org.openrewrite:rewrite-properties`'s sources (`Properties.java`,
`PropertiesParser.java`, `PropertiesPrinter.java`) is the fastest way to understand the pattern
these files follow.

**Scope decision that shapes everything else:** only constructs the JSP spec itself gives meaning
to are parsed into structured nodes — directives (`<%@ %>`), scriptlets/declarations/expressions
(`<% %>`, `<%! %>`, `<%= %>`), JSP comments (`<%-- --%>`), EL expressions (`${}`/`#{}`), and
standard/custom actions (any element whose name contains a namespace prefix, e.g. `jsp:useBean` or
`c:if`). Everything else — **all plain HTML/XML markup** — is preserved verbatim as `Jsp.Text`,
since the JSP translator itself doesn't parse template markup either. A tag is only recognized as
structured `Jsp.Tag` when its name matches `prefix:name`; unprefixed elements like `<div>` are just
literal text.

- `src/main/java/io/resys/openrewrite/jsp/tree/Jsp.java` — the LST model. `Jsp.Document` is the root
  (`SourceFile`); everything else (`Text`, `Comment`, `Directive`, `Declaration`, `Scriptlet`,
  `ExpressionScriptlet`, `ExpressionLanguage`, `Tag`, `Tag.Closing`, `Attribute`,
  `Attribute.Value`) implements `Jsp` and, where applicable, the `Jsp.Content` marker (valid
  children of a `Document` or `Tag` body).
- `JspParser.java` — the hand-written recursive-descent scanner (`parseNodes`/`parseTag`/etc.,
  private nested `Scanner` cursor class) plus the `Parser` interface boilerplate
  (`Input`/`ExecutionContext`/`ParseError` wiring, `.jsp`/`.jspf` file matching).
- `JspVisitor.java` / `JspIsoVisitor.java` — the visitor base classes recipes extend.
- `internal/JspPrinter.java` — regenerates source text from the LST; must reproduce the original
  byte-for-byte when the tree is unmodified (`Parser.requirePrintEqualsInput` enforces this on
  every parse, in both production use and tests).
- `Assertions.java` — `Assertions.jsp(...)` `SourceSpecs` factory for use with
  `RewriteTest.rewriteRun(...)`, mirroring `org.openrewrite.properties.Assertions`.

**Why every non-`Text` node's `prefix` is always `""` from the parser:** whitespace/markup between
recognized JSP constructs is itself arbitrary content (not just whitespace), so it's captured as a
sibling `Jsp.Text` node rather than folded into the following node's `prefix` field (unlike, say,
`Properties`, where inter-entry content really is just whitespace). The `prefix` field still exists
on every node for interface uniformity and so recipes can set it when inserting new nodes.
`Attribute`/`Attribute.Value`/`Tag.Closing` are the exception — their surrounding whitespace *is*
a true prefix, exactly like `Xml.Attribute`.

**A direct consequence for recipe authors:** removing a top-level `Jsp.Content` node (e.g. a
`Directive`) does *not* remove the blank line it stood on — the newline(s) around it live in
sibling `Jsp.Text` nodes that are untouched. `RemoveUnusedTaglibs` (see below) is a real example of
this; it's expected/documented behavior, not a bug to work around.

**Static includes (`<%@ include file="..." %>`)** are resolved at parse time and the target's
parsed nodes are embedded as `Jsp.Directive#getIncludedFile()` (a `Jsp.IncludedFile`). This view
is strictly read-only: `JspPrinter` never prints it, and `JspVisitor#visitDirective` visits it but
throws away the result, so a recipe can never change an included file through the page that
includes it. (The included file is still a normal source file of its own if it's part of the
parse.) Targets are looked up first among the inputs of the same `parseInputs` call, so tests can
supply them as sibling `jsp(..., spec -> spec.path("includes/x.jsp"))` sources, and then on disk.
Relative paths resolve against the including file's directory, and `/`-prefixed ones against the
nearest ancestor containing `WEB-INF`. A missing, unreadable, or unparseable include leaves
`includedFile` as `null` and puts an `org.openrewrite.ParseWarning` marker on the directive saying
why, instead of failing the page (`ParseWarning` is never printed into the source). A recursive
include is also `null`, but has no marker, because its content is already in the tree. Recipes that
hand-walk node lists (as both current recipes do) must explicitly descend into
`getIncludedFile().getNodes()` to see that content: both usage scans do, and both removal passes
deliberately don't. Any recipe that decides something is "unused" must also skip a page with an
unresolved include anywhere in it (including nested inside an included file), because it can't
see what that file uses. Call `UnresolvedIncludes.mustSkip(document, recipeName)` at the top of
`visitDocument`; it logs one `System.Logger` WARNING per unresolved include.

**Tag library descriptors (TLDs)** are resolved for each `<%@ taglib uri="..." %>` by
`internal/TagLibraryResolver`. The result is attached to the directive as a `tree.TagLibrary`
marker: metadata only, never printed. It lists the library's tags (body-content, attributes and
whether they're required, `<variable>`s, dynamic attributes) and EL functions. Lookup order:
1. `JspParser.builder().taglib(uri, path)`, where the path is a `.tld` file, or a jar or directory
   holding a TLD that declares that `<uri>`.
2. `builder().tldSearchPath(...)`: directories, searched recursively for `.tld` files and jars, and
   jars. This is how a Maven project points at JSTL, which isn't in the source tree.
3. The web application, found as the nearest ancestor of the page with a `WEB-INF`: a uri that is
   itself a path to the TLD, then `<taglib>` entries in `WEB-INF/web.xml`, then TLDs under
   `WEB-INF` (excluding `classes`/`lib`), then `WEB-INF/lib/*.jar`, matched by `<uri>`.

An unresolved taglib just gets no marker and no `ParseWarning`. A warning would make the
`RemoveUnused*` recipes skip the page through `UnresolvedIncludes`, and libraries in dependency
jars are routinely unresolvable. TLD XML is parsed with external entities and DTD loading
disabled, because old JSP 1.1 TLDs carry a `DOCTYPE` pointing at a DTD URL. JSP 1.1 element names
(`bodycontent`, `shortname`, `teiclass`) are accepted. In tests, configure the parser with
`spec.parser(JspParser.builder().taglib(...))` and put real files in a `@TempDir` (see
`JspTagLibraryTest`).

**Known, documented limitations** (see the class Javadoc on `Jsp` and `JspParser` for details):
- Scriptlet/declaration/expression code is terminated by the first unescaped `%>`; a `%>` inside a
  Java string/char literal in that code will end the tag early.
- EL expressions embedded inside a quoted attribute value are kept as raw text, not decomposed into
  a sub-tree (top-level EL in template text *is* a first-class `Jsp.ExpressionLanguage` node).
- The all-XML "JSP document" syntax (`.jspx`) and tag files (`.tag`/`.tagx`) are not handled.

**Request-time attribute values.** A quoted attribute value that starts with `<%=` is read up to its
`%>` (then the closing quote), as Jasper does. So `value="<%= bean.get("x") %>"`, with unescaped
quotes inside the Java, parses. Struts 1 pages are full of these, and before this rule existed
they failed to parse entirely (see `JspParserTest#requestTimeAttributeValueWithQuotesInsideTheExpression`).

`Declaration`/`Scriptlet`/`ExpressionScriptlet` expose both `getCode()` (with `%\>` escapes
resolved to a literal `%>`) and `getCodeSource()` (raw, escapes intact) — the same
decoded-getter/`*Source()`-raw-getter split `Properties.Entry` uses for line-continuations.

## Recipes (`receipes` module)

- **`RemoveUnusedTaglibs`** — removes `<%@ taglib prefix="..." ... %>` directives whose prefix is
  never referenced. "Referenced" deliberately covers two cases, not just one: a custom tag
  (`<prefix:tag>`) *and* an EL function reference (`${prefix:function(...)}`), including inside
  attribute values. That second case is what makes this non-trivial — function-only libraries like
  the JSTL functions library (`fn`) are never used as an element, only from EL, so a naive
  "scan for `<prefix:...>` tags" implementation would incorrectly delete a taglib the page still
  needs. The usage scan is deliberately permissive (a raw `prefix:` substring match, not a fully
  parsed EL function call) so mistakes can only lean toward *keeping* an unused taglib, never
  toward wrongly deleting a used one.
- **`RemoveUnusedImports`** — removes unreferenced *and* duplicate classes from
  `<%@ page import="..." %>` directives. `import` is the one `page` attribute that's a
  comma-separated list and may repeat across multiple `<%@ page %>` directives (JSP spec §7.3.3);
  each entry is checked independently against every whole-word occurrence of its simple class name
  across all scriptlets, declarations, and expression scriptlets (including inside custom tag
  bodies). Deduplication is tracked across the *whole page*, not just within one comma list: the
  first occurrence of a given import text is the one subject to the usual unused check, and every
  later occurrence — same comma list or a different `<%@ page import="..." %>` directive further
  down — is dropped outright as redundant, regardless of whether it's independently "used" (an
  entry whose first occurrence was itself unused-and-dropped is *not* treated as "seen", so a
  second occurrence is evaluated on its own merits, not just assumed to be droppable). If removing
  entries empties a directive's `import` attribute, the attribute is dropped; if `import` was that
  directive's only attribute, the whole directive is removed — but a directive with other
  attributes (e.g. `contentType`) alongside `import` only loses the `import` attribute, never the
  whole directive.
  Two deliberately conservative choices, both because getting them wrong deletes something a page
  still needs: wildcard imports (`import="java.util.*"`) are never touched (no way to tell if
  "anything from this package" is used without a real type checker), and usage is a whole-word
  match on the simple name, not a resolved type reference — same "can only false-positive toward
  keeping, never toward deleting" safety property as `RemoveUnusedTaglibs`.
- **`FindModelAttributes`** — an analysis-only `ScanningRecipe<StrutsConfig>` that lists each page's
  inputs in `table.JspModelAttributes`: model attributes, request parameters and Tiles attributes,
  with type and property paths where known. Its scanner collects the form beans and action
  mappings of any `struts-config.xml` (an `Xml.Document` with root `struts-config`) parsed
  alongside the pages. That is why the module depends on `rewrite-xml` at compile scope, and why
  the scanner is a plain `TreeVisitor` rather than a `JspVisitor`. The logic is in
  `ModelAttributeCollector`: a single walk in document order, including included files.
  - **Aliases:** page-local names (`<c:forEach var>`, `<logic:iterate id>`, `<bean:define id>`, Java
    variables assigned from `getAttribute`) are stored as an *alias* (target input + property path
    prefix), so reads through them become property paths on the input (`orders[].total`, where
    `[]` is a collection element).
  - **Scopes:** an `ANY`-scope read merges into (or is upgraded to) a specific-scope read of the
    same name.
  - **Java reads:** Java property reads through aliases are resolved after the walk, over all of
    the page's Java code together, since getters often come in a later scriptlet. Attributes the
    page `setAttribute`s itself stop being inputs from that point on.
  - **Type names:** simple type names are qualified with the page's `<%@ page import %>`s.
- **`FindStaticMethodCalls`** — marks static method calls in Java code with `SearchResult`s and lists
  them in `table.JspStaticMethodCalls`. The `exclusions` option takes `*`/`**` glob patterns on
  `fully.qualified.Class.method`. Detection and import resolution live in `StaticCalls`. Without
  type information it relies on naming conventions; see its Javadoc for what it excludes.
  - **Lines:** come from `JspPositions`, which prints the document once with a `JspPrinter`
    subclass that records each node's start offset. Reuse it for any recipe that reports lines.
  - **Included content:** the base `JspVisitor` visits included files' nodes, discarding the
    results. Visitor-based recipes therefore have to skip nodes under a `Jsp.IncludedFile`
    (`getCursor().firstEnclosing(Jsp.IncludedFile.class)`), or they would report an included
    file's content once per page that includes it.
- **`MigrateJavaVariablesToPageAttributes`** — the first recipe here that rewrites code. It inserts
  `pageContext.setAttribute("x", x);` after every write of a scriptlet variable and turns
  `<%= x %>`/`<%= x.getA() %>` into `${x}`/`${x.a}`.
  - **Where the logic is:** `JavaVariableMigrator` does `analyze` (which decides `MIGRATED`/`SKIPPED`
    per name), then `migrate`. `JavaStatements` is a statement-level scanner. It treats `;`, `{`
    and `}` outside parentheses as statement boundaries and recognizes declarations,
    loop variables (with the insertion point just after the body's `{`) and assignment statements.
  - **The safety rule:** every write found by `JavaStatements.allWrites` must be one of the
    recognized sites, or lie inside the loop header that declares the variable. Otherwise the name
    is skipped, because there would be a write the mirror can't follow.
  - **Variables are tracked by name**, not by Java scope. A name is migrated everywhere on the page
    or nowhere.
  - **Idempotence:** an existing `setAttribute("x", x)` right after a write is not inserted again,
    and an already-mirrored name doesn't count as an EL name conflict. RewriteTest's second cycle
    depends on this.
  - **EL availability:** its scanner reads any parsed `web.xml` (root `web-app`). A missing
    `version` (DTD-based) or one below 2.4 means EL is off by default, so nothing changes.
    `isELIgnored="true"` pages are skipped too.
- **`FindUnmigratableScriptlets`** — the step-5 analysis. `ManualMigrationAnalyzer` *simulates*
  `JavaVariableMigrator(mirrorAll)` + `ScriptletToJstlConverter` on the page. Any original Java node
  whose id still exists in the simulated result as a Java node is `MANUAL`. Its reasons are the
  converter's `Conversion` failures and the variable migrator's `SKIPPED` reasons for the variables
  it declares. Its `Category`s come from regex classification, most specific first.
  - **Ids must survive the simulation**, which is why the converter keeps the original node's id when
    it splits a scriptlet: what's left of `<% stmt; if (c) { %>` (`first.withCode(prefix)`) and of a
    closer's suffix. `withCode`/`withBody` keep ids too, so a scriptlet that only had a mirror
    inserted counts as surviving.
  - **Tests:** they use `spec.after(actual -> actual)` to assert on data tables without spelling out
    every marker.
- **`ConvertScriptletsToJstl`** — converts scriptlet `if`/`else`/loops/assignments/outputs to JSTL
  and EL. It is meant to run after `MigrateJavaVariablesToPageAttributes(mirrorAll)`, and
  `ConvertScriptletsToJstlTest#pipelineFromRawScriptletsToJstl` runs both together.
  - **`JavaToEl`:** a recursive-descent translator for a safe subset of Java expressions. Anything
    outside the subset fails with a reason. EL-visible names are page attributes: step-3 mirrors
    (`setAttribute("x", x)`), `var`/`id` of existing tags, and loop variables inside converted
    `<c:forEach>` bodies.
  - **`ScriptletToJstlConverter`:** finds a block's closer among *sibling* nodes by counting braces
    (`JspPageAnalyzer.braces`). A tag body with unbalanced braces aborts, since the block crosses
    the tag boundary. Conversion builds new `Jsp.Tag`s, including `c:choose` chains.
  - **Loop variables:** a loop's body is converted assuming the loop variable is EL-visible. If
    Java code in the converted body still references the variable, the loop is rejected and its
    body is redone without that assumption.
  - **`c:set`:** `convertAssignments` runs after the structures, because structural conversion is
    what removes the remaining Java references.
  - **Shared settings:** it shares `MigrateJavaVariablesToPageAttributes.WebXml` (and its scanner)
    for the EL-disabled check. `jstlVersion` 1.0 is the exception, because JSTL 1.0 evaluates EL
    itself; outputs are then written as `<c:out escapeXml="false">`.
- **`JspInventory`** — an analysis-only `ScanningRecipe` for migration planning. It fills three data
  tables: `table.JspConstructTotals` (written in `generate()`, after every page has been scanned),
  `table.JspConstructUsage` and `table.JspPageInventory` (both written by the scanner, one page at a
  time). The counting is in `JspInventoryCollector`. Tags and EL functions are keyed by library
  `uri` and local name, not by prefix, because prefixes vary between pages. Included files are read
  only for their taglib prefixes and never counted as part of the including page, so each file's
  content counts once.
- **`FindJspProblems`** — an analysis-only `ScanningRecipe`: it changes nothing, and reports each
  problem as a `SearchResult` marker plus a row in the `table.JspProblems` data table (with page,
  file, line, rule). The rules are listed in its Javadoc: tag balance (`MISSING_END_TAG`,
  `UNMATCHED_END_TAG`, `END_TAG_WITH_ATTRIBUTES`), Java or custom tags inside an HTML start tag
  (`SCRIPTLET_IN_HTML_TAG`), and readability smells (`ELEMENT_CROSSES_BLOCK`, `GENERATED_ATTRIBUTE`,
  `HTML_IN_SCRIPTLET`, `LARGE_SCRIPTLET`, and `JSP_IN_HTML_COMMENT`, which fires when JSP code
  inside `<!-- -->` still runs and asks whether `<%-- --%>` was meant; it is reported once per
  comment and EL is deliberately exempt), and `VARIABLE_FROM_INCLUDE`, for a page that uses a Java
  variable/field/method, bean or scoped attribute defined only in a file it includes. The
  definitions and uses are found by regex in `JspVariables`, not by a real Java or EL parser.
  Java declarations only count when they are at the top level: text nested in `()`/`{}` is
  blanked first, and the analyzer tracks the `javaDepth` of blocks left open across scriptlets.
  A definition in the page itself shadows the included one. When a tag's TLD lists
  `<variable>`s (and has no `tei-class`), those replace the `var=` heuristic: `NESTED` variables
  are ignored, `AT_BEGIN`/`AT_END` ones count. JSTL declares none, so its tags keep using the
  heuristic. With a resolved TLD the analyzer also validates tag usage (`UNKNOWN_TAG`,
  `UNKNOWN_ATTRIBUTE`, `MISSING_REQUIRED_ATTRIBUTE` counting `<jsp:attribute>` children,
  `INVALID_TAG_BODY`, `UNKNOWN_EL_FUNCTION`) against the prefixes in effect at each point of the
  walk, including taglibs declared in included files. A misuse inside an included file is
  reported by the including page only when the taglib was declared outside that file, since the
  file alone couldn't resolve it. The logic lives in `JspPageAnalyzer`. Because HTML is
  only `Jsp.Text` in the LST, it runs its own small HTML tokenizer over the text nodes in document
  order. The tokenizer's state carries across JSP nodes, which is what makes "a scriptlet inside
  `<option ...>`" visible at all. Open elements sit on a stack interleaved with *barriers* (a JSP
  action body, or a Java `{ }` block opened by a scriptlet, from `JspPageAnalyzer.braces`), so an
  element opened in a block and closed outside it is caught. Included files are analyzed in place,
  so a `<div>` opened in a header include and closed in a footer include balances (no
  `MISSING_END_TAG`/`UNMATCHED_END_TAG`) but is reported as `TAG_SPLIT_ACROSS_FILES`, as is a single
  tag split by an include. The `allowSplitBetweenIncludedFiles` option silences the header/footer
  case only: an element whose two tags are in two *included* files. One tag in the page and the
  other in an include is always reported. Problems inside included files are anchored to the
  page's include directive. The scan phase collects every include target. Those files and all
  `.jspf` files are *fragments*, so "left open at end of file" and "end tag with no start tag" are
  not reported for them. A page with an unresolved include gets
  `UNRESOLVED_INCLUDE` and no balance checks. To place a marker at an exact offset inside a
  `Jsp.Text`, the analyzer splits that text node, which still prints identically.

Recipes here follow the `@Value @EqualsAndHashCode(callSuper = false)` declarative style used
throughout OpenRewrite (see `org.openrewrite.xml.RemoveXmlTag` for the canonical example):
`displayName`/`description` as plain fields (Lombok generates the getters `Recipe` requires), any
user-facing parameters as `@Option`-annotated fields, and the actual logic in an overridden
`getVisitor()` returning a `JspIsoVisitor<ExecutionContext>`. Node removal from a `List<Jsp.Content>`
uses `ListUtils.map(list, node -> shouldRemove(node) ? null : node)` — returning `null` from the
mapping function is the standard OpenRewrite idiom for "delete this element" (see
`org.openrewrite.properties.DeleteProperty` for another example).

## Testing conventions

Tests use `RewriteTest` + `Assertions.jsp(...)`, e.g.:

```java
class MyTest implements RewriteTest {
    @Test
    void example() {
        rewriteRun(jsp("<%@ page contentType=\"text/html\" %>"));
    }
}
```

Passing only a "before" source (no recipe, no "after") asserts a parse → print round trip that
reproduces the input exactly — this is the primary way the `parser` module is tested (see
`JspParserTest`), since correctness there means byte-for-byte print fidelity across every JSP
construct in scope, not recipe behavior. When adding a new construct to the parser, add a
round-trip test for it before anything else.

For recipe tests that pass both a "before" and an "after" (see `RemoveUnusedTaglibsTest`): the
harness runs `after` through `trimIndentPreserveCRLF` (stripping exactly one leading and one
trailing blank line, as a text-block-fixture convention) but does **not** trim the actual printed
output the same way. Concretely: an interior blank line in expected output round-trips fine in a
text block, but if the real result's first or last character is a newline (e.g. immediately after
removing a node that leaves a leading blank line, per the note above), that leading/trailing blank
line cannot be expressed as a leading/trailing blank line in the text block — write a test fixture
where the relevant content isn't first/last in the file instead (e.g. put a `page` directive before
the directive under test) rather than fighting the framework.

Malformed input must never hang or throw past the parser boundary — `JspParser.parseInputs`
catches parsing failures and returns `org.openrewrite.tree.ParseError` instead, per the
`org.openrewrite.Parser` contract. Two kinds of malformed markup are deliberately *recovered from*
rather than failed, so that `FindJspProblems` can report them (a `ParseError` page is invisible to
recipes):
- A custom/standard action missing its end tag gets `closing == null`. Its body ends at the end of
  input or at an *enclosing* action's end tag.
- An end tag with attributes (`</c:if test="x">`) keeps that trailing text verbatim in
  `Tag.Closing#getBeforeTagDelimiterPrefix()`.

Code that reads `Jsp.Tag#getClosing()` must handle `null` even when `isSelfClosing()` is false.
