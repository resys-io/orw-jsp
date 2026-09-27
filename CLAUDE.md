# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An [OpenRewrite](https://docs.openrewrite.org/) language module for JSP: a Lossless Semantic Tree
(LST) parser, printer, and visitor infrastructure for the "standard syntax" defined by
[Jakarta Server Pages 4.0](https://jakarta.ee/specifications/pages/4.0/jakarta-server-pages-spec-4.0)
(`<% %>`-style constructs). This lets OpenRewrite recipes read, search, and rewrite `.jsp`/`.jspf`
files the same way `rewrite-java`, `rewrite-xml`, etc. do for their languages.

Coordinates: `io.resys.openrewrite:openrewrite-jsp-parser:1.0-SNAPSHOT`, Java 21, built against
OpenRewrite 8.90.4 (`rewrite-bom`) and JUnit 6.1.3 (`junit-bom`).

## Build

Maven, toolchain-free (no wrapper is checked in — `.mvn/` is empty). Requires JDK 21.

```sh
mvn compile                              # compile main sources
mvn test                                  # run all tests
mvn test -Dtest=JspParserTest#pageDirective   # single test method
mvn package                              # build the jar into target/
```

Lombok (`@Value`/`@With`/`@EqualsAndHashCode`) generates the LST's boilerplate at compile time via
annotation processing — no extra Maven config needed, but an IDE must have its Lombok plugin
enabled to resolve the generated accessors/`with*` methods.

## Architecture

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
  `RewriteTest.rewriteRun(...)`, mirroring `org.openrewrite.properties.Assertions`. It lives in
  `src/main` (not `src/test`) so recipe authors depending on this jar get it too; that's why
  `rewrite-test` is a `provided`-scope dependency in `pom.xml` rather than `test`-scope.

**Why every non-`Text` node's `prefix` is always `""` from the parser:** whitespace/markup between
recognized JSP constructs is itself arbitrary content (not just whitespace), so it's captured as a
sibling `Jsp.Text` node rather than folded into the following node's `prefix` field (unlike, say,
`Properties`, where inter-entry content really is just whitespace). The `prefix` field still exists
on every node for interface uniformity and so recipes can set it when inserting new nodes.
`Attribute`/`Attribute.Value`/`Tag.Closing` are the exception — their surrounding whitespace *is*
a true prefix, exactly like `Xml.Attribute`.

**Known, documented limitations** (see the class Javadoc on `Jsp` and `JspParser` for details):
- Scriptlet/declaration/expression code is terminated by the first unescaped `%>`; a `%>` inside a
  Java string/char literal in that code will end the tag early.
- EL expressions embedded inside a quoted attribute value are kept as raw text, not decomposed into
  a sub-tree (top-level EL in template text *is* a first-class `Jsp.ExpressionLanguage` node).
- The all-XML "JSP document" syntax (`.jspx`) and tag files (`.tag`/`.tagx`) are not handled.

`Declaration`/`Scriptlet`/`ExpressionScriptlet` expose both `getCode()` (with `%\>` escapes
resolved to a literal `%>`) and `getCodeSource()` (raw, escapes intact) — the same
decoded-getter/`*Source()`-raw-getter split `Properties.Entry` uses for line-continuations.

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
reproduces the input exactly — this is the primary way this module is tested (see
`JspParserTest`), since correctness here means byte-for-byte print fidelity across every JSP
construct in scope, not recipe behavior. When adding a new construct to the parser, add a
round-trip test for it before anything else.

Malformed input must never hang or throw past the parser boundary — `JspParser.parseInputs`
catches parsing failures and returns `org.openrewrite.tree.ParseError` instead, per the
`org.openrewrite.Parser` contract.
