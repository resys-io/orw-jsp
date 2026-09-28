# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An [OpenRewrite](https://docs.openrewrite.org/) language module for JSP: a Lossless Semantic Tree
(LST) parser, printer, and visitor infrastructure for the "standard syntax" defined by
[Jakarta Server Pages 4.0](https://jakarta.ee/specifications/pages/4.0/jakarta-server-pages-spec-4.0)
(`<% %>`-style constructs), plus a growing set of recipes built on top of it. This lets OpenRewrite
recipes read, search, and rewrite `.jsp`/`.jspf` files the same way `rewrite-java`, `rewrite-xml`,
etc. do for their languages.

Root coordinates: `io.resys.openrewrite:resys-openrewrite-jsp:1.0-SNAPSHOT` (a `pom`-packaged
reactor, no code of its own). Java 21, built against OpenRewrite 8.90.4 (`rewrite-bom`) and JUnit
6.1.3 (`junit-bom`), both pinned in the root `pom.xml` and inherited by every module.

**Note on spelling:** the recipes module and its package are spelled `receipes` (not `recipes`)
throughout — `artifactId=resys-openrewrite-jsp-receipes`, package
`io.resys.openrewrite.jsp.receipes`. This is a pre-existing typo baked into the directory layout,
artifactId, and package name consistently; match it exactly in new code rather than "fixing" only
part of it (which would just create an inconsistent mix of both spellings).

## Modules

- **`parser`** (`resys-openrewrite-jsp-parser`) — the LST itself: `Jsp` tree model, `JspParser`,
  `JspVisitor`/`JspIsoVisitor`, `JspPrinter`, and the `Assertions.jsp(...)` test helper. See
  **Parser architecture** below.
- **`receipes`** (`resys-openrewrite-jsp-receipes`) — `Recipe` subclasses built on the parser, e.g.
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
resolve the generated accessors/`with*` methods.

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

**Known, documented limitations** (see the class Javadoc on `Jsp` and `JspParser` for details):
- Scriptlet/declaration/expression code is terminated by the first unescaped `%>`; a `%>` inside a
  Java string/char literal in that code will end the tag early.
- EL expressions embedded inside a quoted attribute value are kept as raw text, not decomposed into
  a sub-tree (top-level EL in template text *is* a first-class `Jsp.ExpressionLanguage` node).
- The all-XML "JSP document" syntax (`.jspx`) and tag files (`.tag`/`.tagx`) are not handled.

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
`org.openrewrite.Parser` contract.
