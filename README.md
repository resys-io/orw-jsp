# OpenRewrite JSP

An [OpenRewrite](https://docs.openrewrite.org/) language module for JSP pages (`.jsp`, `.jspf`).
It provides a lossless parser for the standard JSP syntax of
[Jakarta Server Pages 4.0](https://jakarta.ee/specifications/pages/4.0/jakarta-server-pages-spec-4.0),
plus recipes that clean up and analyze JSP pages.

| Module | Artifact | Contents |
|---|---|---|
| `parser` | `io.resys.openrewrite:resys-openrewrite-jsp-parser` | The JSP syntax tree, `JspParser`, visitors, printer, and the `Assertions.jsp(...)` test helper |
| `receipes` | `io.resys.openrewrite:resys-openrewrite-jsp-receipes` | The recipes described below |

Both are version `1.0-SNAPSHOT`, built for Java 21 against OpenRewrite 8.90.4.

```sh
mvn clean install
```

## Running the recipes

> **The OpenRewrite Maven and Gradle plugins can't run these recipes.** They parse projects with a
> fixed set of built-in parsers and read any other file, including `.jsp`, as plain text. The
> recipes only act on files parsed by `JspParser`, so under `mvn rewrite:run` they would silently
> do nothing.

Run them from code instead: parse the pages with `JspParser`, then run a recipe on the result.

```java
ExecutionContext ctx = new InMemoryExecutionContext(Throwable::printStackTrace);

List<Path> jsps;
try (Stream<Path> files = Files.walk(project.resolve("src/main/webapp"))) {
    jsps = files.filter(p -> p.toString().endsWith(".jsp") || p.toString().endsWith(".jspf")).toList();
}

List<SourceFile> sources = JspParser.builder()
        .tldSearchPath(project.resolve("target/dependency"))   // see "Parser configuration"
        .build()
        .parse(jsps, project, ctx)
        .toList();

// Analysis: FindJspProblems reports problems and changes nothing.
RecipeRun analysis = new FindJspProblems(null, null).run(new InMemoryLargeSourceSet(sources), ctx);
List<JspProblems.Row> problems = analysis.getDataTableRows(JspProblems.class);
for (JspProblems.Row problem : problems) {
    System.out.printf("%s:%d %s %s%n", problem.getFile(), problem.getLine(), problem.getRule(), problem.getMessage());
}

// Cleanup: write back the files a recipe changed.
RecipeRun cleanup = new RemoveUnusedTaglibs().run(new InMemoryLargeSourceSet(sources), ctx);
for (Result result : cleanup.getChangeset().getAllResults()) {
    SourceFile after = result.getAfter();
    if (after != null) {
        Files.writeString(project.resolve(after.getSourcePath()), after.printAll(), after.getCharset());
    }
}
```

Parse all pages of a web application together in one `parse` call. Static includes are resolved
from the same batch first, then from disk.

## Parser configuration

Configure the parser through `JspParser.builder()`. The builder settings are for tag library
descriptors (TLDs); static includes need no configuration.

### Static includes

`<%@ include file="..." %>` is resolved while parsing. The included file's content is embedded in
the including page's tree so recipes can take it into account. It is **read-only**: it is never
printed, and recipes can't change an included file through the page that includes it.

- A relative `file` is resolved against the directory of the file containing the directive.
- A `/`-prefixed `file` is resolved against the web application root: the nearest ancestor folder
  that contains `WEB-INF`.
- An include that can't be found, read, or parsed gets a warning. The cleanup recipes then skip
  the page, and `FindJspProblems` reports `UNRESOLVED_INCLUDE`.

### Tag library descriptors (TLDs)

Each `<%@ taglib uri="..." %>` is resolved to its TLD where possible. `FindJspProblems` uses it to
validate tags and EL functions, and to know which variables a tag defines. The parser tries these
in order:

1. **Explicit mapping**: `taglib(uri, path)`, where the path is a `.tld` file, or a jar or folder
   containing a TLD that declares that `<uri>`.
2. **Search path**: `tldSearchPath(paths...)`, a list of folders (searched recursively for `.tld`
   files and jars) and jars, matched by each TLD's `<uri>`.
3. **The web application**, found from the page's location, the way a servlet container does it:
   - a `uri` that is itself a path to the TLD (`uri="/WEB-INF/app.tld"`),
   - `<taglib>` entries in `WEB-INF/web.xml`,
   - TLDs under `WEB-INF`,
   - TLDs in `WEB-INF/lib/*.jar`.

```java
JspParser parser = JspParser.builder()
        .taglib("http://example.com/app", Path.of("src/main/webapp/WEB-INF/app.tld"))
        .taglib("http://example.com/legacy", Path.of("lib/legacy-tags.jar"))
        .tldSearchPath(Path.of("target/dependency"))
        .build();
```

In a Maven project, libraries like JSTL live in dependency jars rather than in `WEB-INF/lib`, so
the web-application lookup won't find them. Copy them out with `mvn dependency:copy-dependencies`
and add `target/dependency` to the search path, or map their URIs explicitly.

An unresolved taglib is not an error. Its tags just aren't validated. Old JSP 1.1-style TLDs are
supported, and any DTD a TLD references is never downloaded.

## Recipes

All recipes are in the `io.resys.openrewrite.jsp.receipes` package.

### `RemoveUnusedTaglibs`

Removes `<%@ taglib prefix="..." %>` directives whose prefix is never used on the page.

A prefix counts as used if it appears as a tag (`<c:if>`) or as an EL function (`${fn:length(list)}`).
EL functions matter because function-only libraries such as JSTL `fn` never appear as tags. Uses
inside statically included files count too.

```jsp
<%-- before --%>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>
<%@ taglib prefix="x" uri="jakarta.tags.xml" %>
<c:out value="${fn:length(items)}"/>

<%-- after --%>
<%@ taglib prefix="c" uri="jakarta.tags.core" %>
<%@ taglib prefix="fn" uri="jakarta.tags.functions" %>

<c:out value="${fn:length(items)}"/>
```

- **No options.**
- **Errs on the side of keeping:** the usage check is a loose text match, so a mistake can only
  keep an unused taglib, never remove a used one.
- **The blank line stays:** removing a directive leaves the empty line it was on.
- **Skips pages with an unresolved include,** because what the missing file uses is unknown. It
  logs a `WARNING` naming the page and the include.

### `RemoveUnusedImports`

Removes unused and duplicate classes from `<%@ page import="..." %>` directives.

An import counts as used if its simple class name appears as a whole word in the page's Java
code: scriptlets, declarations, and `<%= %>` expressions, including those in included files.

```jsp
<%-- before --%>
<%@ page import="java.util.List, java.util.Date, java.io.File" %>
<%@ page import="java.util.List" %>
<% List<String> items = load(); %>

<%-- after --%>
<%@ page import="java.util.List" %>

<% List<String> items = load(); %>
```

- **No options.**
- **Duplicates:** the first occurrence of an import is kept if it's used. Later repeats, in the same
  list or another directive, are removed.
- **Emptied directives:** if the `import` attribute ends up empty, it's removed. If it was the
  directive's only attribute, the whole directive is removed.
- **Wildcards are never removed:** whether anything from `java.util.*` is used can't be checked
  without a real type checker.
- **Errs on the side of keeping:** the usage check is a plain word match, so a mistake can only
  keep an unused import.
- **Skips pages with an unresolved include,** and logs a `WARNING`, like `RemoveUnusedTaglibs`.

### `JspInventory`

An analysis recipe for planning a migration. It lists which JSP constructs the pages use, how
often, and on how many pages. **It changes nothing.** It has no options and fills three data
tables:

| Data table | One row per | Columns |
|---|---|---|
| `JspConstructTotals` | construct, across all pages, most used first | `category`, `construct`, `library`, `totalCount`, `pageCount` |
| `JspConstructUsage` | page and construct | `sourcePath`, `category`, `construct`, `library`, `count` |
| `JspPageInventory` | page | `sourcePath`, `lines`, `scriptlets`, `javaLines`, `expressions`, `declarations`, `elExpressions`, `customTags`, `includes`, `tagLibraries` |

```java
RecipeRun run = new JspInventory().run(new InMemoryLargeSourceSet(sources), ctx);
List<JspConstructTotals.Row> totals = run.getDataTableRows(JspConstructTotals.class);
```

It counts these categories of constructs:

| Category | Constructs |
|---|---|
| `TAG` | Every prefixed tag, e.g. `html:form`, `logic:iterate`, `jsp:include` |
| `EL_FUNCTION` | EL function calls, e.g. `fn:length` |
| `EL_IMPLICIT_OBJECT` | `sessionScope`, `param`, `pageContext`, … in EL |
| `DIRECTIVE` | `page`, `taglib`, `include` |
| `SCRIPTING` | `scriptlet`, `expression`, `declaration`, and `expression in tag attribute` (`<html:text value="<%= x %>"/>`, common in Struts 1) |
| `JAVA_IMPLICIT_OBJECT` | `request.`, `session.`, `out.`, `pageContext.`, … used from Java code |

- **Tags are grouped by library:** tags and EL functions are attributed to the taglib `uri` (or
  `tagdir:` path) their prefix is declared with. So `<html:form>` on one page and `<h:form>` on
  another both count under the Struts HTML library. Prefixes declared in included files count
  too. A `jsp:` tag shows as `(JSP standard action)`; a prefix with no declaration shows as
  `(undeclared prefix)`.
- **No double counting:** each file's content counts once, for that file. An included file is not
  counted again as part of the pages that include it.
- **Different URIs aren't merged:** libraries are compared by URI exactly as written, so a library
  referenced by two different URIs, such as an old `/WEB-INF/struts-html.tld` path and
  `http://struts.apache.org/tags-html`, shows up as two libraries.

### `FindModelAttributes`

An analysis recipe that documents what each page expects to be given: the model attributes a
controller must provide, plus request parameters and Tiles attributes. Where the page reveals it,
it also records each input's type and the property paths the page reads from it. These paths are
what Spring MVC model classes need. **It changes nothing.** It has no options and fills one data
table, `JspModelAttributes`, with one row per page and input:

| Column | Description |
|---|---|
| `sourcePath` | The page |
| `scope` | `REQUEST`, `SESSION` or `APPLICATION` when read from that scope. `ANY` when read from whichever scope has it (EL, `findAttribute`, Struts tags without `scope`). `PARAMETER` for a request parameter, `TILES` for a Tiles attribute. |
| `name` | The attribute or parameter name. `(non-literal key: Constants.X)` when Java code reads it by a constant. |
| `type` | The type, when the page reveals it. Simple names are fully qualified using the page's `import`s. |
| `properties` | Property paths read from it, with `: Type` where known, e.g. `address.city, orders[]: com.acme.Order, orders[].total`. `[]` means an element of a collection. |
| `readBy` | How it's read, e.g. `EL`, `request.getAttribute`, `bean:write`, `struts-config form bean for action /save` |
| `files` | Where it's read: the page and/or files it includes |

For example, this page:

```jsp
<%@ page import="java.util.List, com.acme.User, com.acme.Order" %>
<%
    User user = (User) request.getAttribute("user");
    List<Order> orders = (List<Order>) session.getAttribute("orders");
    for (Order o : orders) { out.print(o.getTotal()); }
%>
${user.address.city}
<c:forEach var="line" items="${cart.lines}">${line.product.name}</c:forEach>
```

produces these rows:

| scope | name | type | properties |
|---|---|---|---|
| `REQUEST` | `user` | `com.acme.User` | `address.city` |
| `SESSION` | `orders` | `java.util.List<com.acme.Order>` | `[]: com.acme.Order, [].total` |
| `ANY` | `cart` | | `lines, lines[], lines[].product.name` |

**What it recognizes**

- **Java code**
  - Reads: `request`/`session`/`application`/`getServletContext()`/`request.getSession()` with
    `.getAttribute("x")`, `pageContext.findAttribute("x")` (and `getAttribute` with a scope
    constant), and `request.getParameter("p")`/`getParameterValues("p")`.
  - The type comes from the cast, or from the declared type of the variable the value is
    assigned to.
  - Property paths come from getter chains on that variable (`u.getAddress().getCity()`),
    for-each loops over it, and typed assignments (`String n = u.getName();` gives `name: String`).
- **EL**
  - Root names and paths: `${user.address.city}`, `${user['name']}`, `${requestScope.x}`,
    `${sessionScope.x}`, `${param.p}`.
  - `<c:forEach var items>` makes `var` stand for the elements of `items`.
- **Struts 1 tags**
  - `name`/`property`/`scope` on `bean:write`, `bean:define`, `bean:size`, the `logic:`
    comparison and presence tags, and `logic:iterate` (its `type` gives the element type).
  - `logic:* parameter="p"` reads a request parameter.
  - An `html:` input field's `property` is a property of the enclosing `<html:form>`'s form bean,
    or of the bean named by its `name`. `html:options collection` and `html:optionsCollection`
    are covered too.
  - Struts taglibs are recognized by URI, including old `/WEB-INF/struts-*.tld` paths.
- **Struts form beans** (see below)
- **Tiles:** `tiles:getAsString`, `tiles:insert attribute`, `tiles:useAttribute`,
  `tiles:importAttribute`.
- **Standard actions:** `<jsp:useBean>` in request, session or application scope (with its
  `class`/`type`), and `<jsp:getProperty>`/`<jsp:setProperty>`.

**Not counted as inputs:** names the page defines before using them, such as `<c:set var>`,
`<bean:define value>`, iteration variables, page-scoped beans, and attributes the page sets with
`setAttribute`. Reads inside statically included files count as reads of the including page.

**Struts form beans.** Parse `struts-config.xml` together with the pages, using OpenRewrite's XML
parser. `<html:form action="/save.do">` then resolves to the attribute name, scope (Struts'
default is `session`), type and, for a `DynaActionForm`, typed properties of its form bean:

```java
List<SourceFile> sources = new ArrayList<>(jspSources);
sources.addAll(XmlParser.builder().build()
        .parse(List.of(project.resolve("src/main/webapp/WEB-INF/struts-config.xml")), project, ctx)
        .toList());
RecipeRun run = new FindModelAttributes().run(new InMemoryLargeSourceSet(sources), ctx);
List<JspModelAttributes.Row> inputs = run.getDataTableRows(JspModelAttributes.class);
```

Without `struts-config.xml`, a form bean is listed as `(form bean of action /save.do)`, with the
properties its fields read.

**Limitations:** this is text matching, not a real Java or EL parser, so unusual code can be
missed. A type is only as precise as the page states it.

### `FindStaticMethodCalls`

Finds static method calls in the Java code of pages, since a migration has to move them out of
the view. It looks in scriptlets, `<%= %>` expressions, `<%! %>` declarations, and `<%= %>` inside
custom tag attributes (`<html:text value="<%= StringUtils.trim(v) %>"/>`). **It changes nothing**
except adding markers. Each call is marked in the page (`~~(Static method call
com.acme.util.DateUtils.format())~~>`) and listed in the `JspStaticMethodCalls` data table:

| Column | Description |
|---|---|
| `sourcePath` | The page |
| `line` | Line of the call |
| `className` | The class, fully qualified through the page's imports when that's unambiguous; otherwise as written |
| `method` | The method name |
| `candidates` | When a wildcard import makes the class ambiguous, the classes it may be, e.g. `java.util.Collections, Collections` |
| `context` | `scriptlet`, `expression`, `declaration`, or `tag attribute` |

#### Options

| Option | Type | Default | Description |
|---|---|---|---|
| `exclusions` | `List<String>` | none | Calls not to report, as patterns matched against `fully.qualified.Class.method`. `*` matches within one name segment, `**` across segments. A call with an ambiguous class is excluded if any of its candidates matches. |

```java
new FindStaticMethodCalls(List.of(
        "java.lang.Math.*",                       // every Math method
        "java.lang.Integer.parseInt",             // one method
        "org.apache.commons.lang.StringUtils.*",  // a utility class Thymeleaf can call with T(...)
        "com.acme.util.**"))                      // a whole package and its subpackages
```

**How calls are recognized.** There is no type checker, so it goes by Java naming conventions:
`Class.method(…)` with a capitalized class name, `Outer.Inner.method(…)`, `Class.<T>method(…)`,
or a fully qualified `com.acme.Util.method(…)`. These are not counted as static calls:

- calls on a capitalized variable the page declares,
- constructors (`new Outer.Inner()`),
- instance methods on constants (`Status.ACTIVE.name()`),
- anything inside string literals or comments.

**How classes are resolved.** The page's `<%@ page import %>`s are used, including those in
included files, which are compiled into the same servlet.

- An explicit import, or a common `java.lang` class like `Integer` or `Math`, gives the full name.
- A wildcard import gives a list of candidates instead.
- Calls inside an included file are reported for that file, not for each page that includes it.

### `MigrateJavaVariablesToPageAttributes`

**This recipe changes pages.** It copies Java variables declared in scriptlets into page-scoped
attributes, and turns `<%= %>` expressions that only output them into EL. The values then become
usable from EL and JSTL, which is the first step toward replacing scriptlets:

```jsp
<%-- before --%>
<% String name = user.getName(); %>
<p><%= name %></p>
<% for (Order o : orders) { %>
  <td><%= o.getTotal() %></td>
<% } %>

<%-- after --%>
<% String name = user.getName(); pageContext.setAttribute("name", name); %>
<p>${name}</p>
<% for (Order o : orders) { pageContext.setAttribute("o", o); %>
  <td>${o.total}</td>
<% } %>
```

- **Java code keeps working:** the Java variable stays, so other Java code is untouched.
- **Where `setAttribute` goes:** after each declaration with an initializer, at the start of each
  braced loop body declaring the variable, and after every assignment statement (`x = …;`,
  `x += …;`, `x++;`), so the page attribute never goes stale.
- **Which outputs become EL:** an output is converted if it is the variable alone, or the variable
  followed by `get…()` getters: `<%= o.getCustomer().getName() %>` becomes `${o.customer.name}`.
- **Running it again changes nothing more:** it recognizes the `setAttribute`s it already added.

#### Options

| Option | Type | Default | Description |
|---|---|---|---|
| `convertTagAttributes` | `Boolean` | `false` | Also convert a custom tag attribute that is exactly `<%= x %>` to `${x}`. Only safe if the tag evaluates EL in that attribute: a JSP 2.0+ container with `rtexprvalue` attributes, or EL-aware tags. |
| `mirrorAll` | `Boolean` | `false` | Also copy variables that no `<%= %>` outputs into page attributes, e.g. to use them from JSTL conditions later. |

#### Left unchanged

A variable is left unchanged in these cases. Each one is reported with its reason in the
`JspVariableMigrations` data table (`sourcePath`, `variable`, `status` = `MIGRATED`/`SKIPPED`,
`reason`, `declarations`, `convertedUses`):

- **Written where no statement can follow:** inside an expression
  (`while ((line = r.readLine()) != null)`), a braceless `if`/`else`/loop body, or a `case`
  label. The same applies to a loop without braces that declares it.
- **The name is already in use:** the page uses it in EL or as a tag's `name`/`var`/`id`, and a
  page attribute with that name would hide the existing value.
- **The name means something else in EL:** an EL reserved word or implicit object (`param`,
  `header`, `empty`, …).
- **A `<%! %>` field:** it is shared by all requests.
- **Never output:** no `<%= %>` outputs it, unless `mirrorAll` is set.

`is…()` getters stay Java, because EL only recognizes them on primitive `boolean` properties. A
variable declared only in an included file is migrated in that file, when the recipe runs on it.

#### EL must be enabled

Nothing is changed on a page with `<%@ page isELIgnored="true" %>`. Struts 1 applications often
have a Servlet 2.3 `web.xml` (version below 2.4, or DTD-based), in which **EL is off by default**.
Parse `web.xml` together with the pages (with `XmlParser`, as for `struts-config.xml`), and the
recipe changes nothing and reports why. Without a parsed `web.xml` it assumes EL is enabled.

**One output difference:** `<%= x %>` prints `null` for a null value; `${x}` prints nothing.

### `FindJspProblems`

An analysis recipe that finds structural and readability problems. **It changes nothing.** It
reports each problem in two ways:

- **A marker in the source** (`SearchResult`), shown in OpenRewrite diffs as
  `~~(RULE: message)~~>` right where the problem starts. A problem inside an included file is
  marked on the page's `<%@ include %>` line.
- **A row in the `JspProblems` data table** (`io.resys.openrewrite.jsp.receipes.table.JspProblems`),
  read with `RecipeRun#getDataTableRows(JspProblems.class)`:

| Column | Description |
|---|---|
| `sourcePath` | The page that was analyzed |
| `file` | The file the problem is in: the page, or a file it includes |
| `line` | Line number in `file` |
| `rule` | The rule that found it (see below) |
| `message` | A description of the problem |

#### Options

| Option | Type | Default | Description |
|---|---|---|---|
| `maxScriptletLines` | `Integer` | `20` | Scriptlets and declarations with more non-blank lines than this are reported as `LARGE_SCRIPTLET`. |
| `allowSplitBetweenIncludedFiles` | `Boolean` | `false` | Don't report an element whose start and end tags are in two different *included* files, e.g. `<div>` in `header.jspf` and `</div>` in `footer.jspf`. A split where one of the tags is in the page itself is still reported. |

```java
new FindJspProblems(30, true)   // maxScriptletLines = 30, allowSplitBetweenIncludedFiles = true
```

#### Rules

**Tag balance**

| Rule | Reported for | Example |
|---|---|---|
| `MISSING_END_TAG` | An element whose end tag is required in HTML, or a custom tag, that is never closed or only closed implicitly by an outer end tag | `<div><span>text</div>` |
| `UNMATCHED_END_TAG` | An end tag with no matching start tag | `</span>` with no `<span>` |
| `END_TAG_WITH_ATTRIBUTES` | An end tag with attributes, which browsers ignore | `</div class="a">` |
| `ELEMENT_CROSSES_BLOCK` | An element opened inside a Java block or custom tag body and closed outside it, so the page's structure depends on which branch runs | `<% if (a) { %><div class="x"><% } else { %><div class="y"><% } %>…</div>` |
| `TAG_SPLIT_ACROSS_FILES` | An element whose start and end tags are in different files, or one tag cut by an include | `<div>` in `header.jspf`, `</div>` in `footer.jspf`; `<div <%@ include file="attrs.jspf" %>>` |

HTML elements whose end tag is optional (`<p>`, `<li>`, `<td>`, `<option>`, …) and void elements
(`<br>`, `<img>`, …) are not reported as unclosed. Content inside `<script>`, `<style>` and HTML
comments is not checked for tags.

**Readability**

| Rule | Reported for | Example |
|---|---|---|
| `SCRIPTLET_IN_HTML_TAG` | Java code or a custom tag with a body inside an HTML start tag | `<option <% if (sel) { %>selected<% } %>>` |
| `GENERATED_ATTRIBUTE` | An expression that outputs attributes rather than an attribute value | `<input <%= checked ? "checked" : "" %>>` |
| `HTML_IN_SCRIPTLET` | Markup written from Java strings | `<% out.println("<td>" + x + "</td>"); %>` |
| `LARGE_SCRIPTLET` | A scriptlet or declaration longer than `maxScriptletLines` | |
| `JSP_IN_HTML_COMMENT` | JSP code inside `<!-- -->`, where it still runs on the server. Asks whether a JSP comment `<%-- --%>` was intended. Reported once per comment. EL is exempt. | `<!-- <% oldCode(); %> -->` |
| `VARIABLE_FROM_INCLUDE` | The page uses a variable defined only in a file it includes. Reported once per variable, at its first use. | `<%= userName %>` where `userName` is declared in `init.jspf` |

`VARIABLE_FROM_INCLUDE` covers:
- Java variables declared at the top level of a scriptlet.
- Fields and methods from `<%! %>`.
- `<jsp:useBean>` beans.
- Scoped attributes: `<c:set var>` and other tags with a `var` attribute, and
  `request`/`pageContext`/`session`/`application.setAttribute(...)`.

It looks for uses in Java code, EL (`${x}`, `${requestScope.x}`), `getAttribute("x")` and
`<jsp:getProperty name="x">`. It doesn't report a variable declared inside a block or a
`<c:forEach>` body, or a name the page defines itself.

**Tag library validation** (only for taglibs whose TLD was resolved; see
[Parser configuration](#tag-library-descriptors-tlds))

| Rule | Reported for | Example |
|---|---|---|
| `UNKNOWN_TAG` | A tag the library doesn't define, with a "did you mean" suggestion | `<c:iff>` → "did you mean 'if'?" |
| `UNKNOWN_ATTRIBUTE` | An attribute the tag doesn't declare, unless the tag accepts any attribute | `<c:out vaule="…"/>` |
| `MISSING_REQUIRED_ATTRIBUTE` | A required attribute that is missing. A `<jsp:attribute name="…">` child counts as present. | `<c:out/>` |
| `INVALID_TAG_BODY` | Content inside a tag declared `empty`, or Java code inside a tag declared `scriptless` | `<c:remove var="x">text</c:remove>` |
| `UNKNOWN_EL_FUNCTION` | An EL function the library doesn't define | `${fn:lenght(list)}` |

When a tag's TLD declares variables, `VARIABLE_FROM_INCLUDE` uses those instead of guessing from
`var=` (variables that only exist inside the tag's body are ignored). JSTL's TLDs declare none,
so JSTL tags keep using the `var=` rule.

**Other**

| Rule | Reported for |
|---|---|
| `UNRESOLVED_INCLUDE` | A static include that couldn't be resolved. Tag balance is not checked for that page, because the missing file may open or close elements. |

#### Fragments and includes

- **Included files are checked in place**, as part of each page that includes them. A `<div>`
  opened in a header include and closed in a footer include is balanced, though reported as
  `TAG_SPLIT_ACROSS_FILES` unless `allowSplitBetweenIncludedFiles` is on.
- **Fragments may leave markup open or close markup they didn't open.** A fragment is a `.jspf`
  file, or any file another page in the same run includes. Those two balance problems aren't
  reported for fragments; everything else is.

## Writing tests

The `parser` module ships `Assertions.jsp(...)` for OpenRewrite's `RewriteTest`:

```java
class MyRecipeTest implements RewriteTest {
    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveUnusedTaglibs());
    }

    @Test
    void removesUnusedTaglib() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="text/html" %>
                        <%@ taglib prefix="x" uri="jakarta.tags.xml" %>
                        <html></html>
                        """,
                        """
                        <%@ page contentType="text/html" %>

                        <html></html>
                        """
                )
        );
    }
}
```

- **Removed lines stay blank:** removing a directive leaves its line blank. Expected output that
  would start or end with that blank line can't be written as a text block, so keep other content
  around it, as above.
- **Includes:** give sibling sources explicit paths, e.g.
  `jsp("...", spec -> spec.path("includes/header.jspf"))`.
- **TLDs:** configure the parser with `spec.parser(JspParser.builder().taglib(uri, tldPath))`.

## Known limitations

- **HTML is not parsed into a tree.** Only JSP constructs are: directives, scriptlets,
  expressions, EL, comments, and prefixed tags like `<c:if>`. Plain HTML is kept as text, and
  `FindJspProblems` scans it with a lightweight tokenizer.
- **`%>` inside Java code:** a `%>` inside a Java string in a scriptlet ends the scriptlet early.
- **EL inside custom tag attribute values** is kept as raw text, not parsed into a tree.
- **Unsupported file types:** XML-syntax JSP documents (`.jspx`) and tag files (`.tag`/`.tagx`)
  aren't parsed. Taglibs declared with `tagdir="..."` aren't resolved, so their tags aren't validated.
- **Approximate Java and EL analysis:** variable and import usage is detected by text matching,
  not by a real Java or EL parser.
