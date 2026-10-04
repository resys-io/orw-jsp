# OpenRewrite JSP

An [OpenRewrite](https://docs.openrewrite.org/) language module for JSP pages (`.jsp`, `.jspf`).
It provides a lossless parser for the standard JSP syntax of
[Jakarta Server Pages 4.0](https://jakarta.ee/specifications/pages/4.0/jakarta-server-pages-spec-4.0),
plus recipes that clean up and analyze JSP pages.

| Module | Artifact | Contents |
|---|---|---|
| `parser` | `io.resys.orw:resys-orw-jsp-parser` | The JSP syntax tree, `JspParser`, visitors, printer, and the `Assertions.jsp(...)` test helper |
| `receipes` | `io.resys.orw:resys-orw-jsp-receipes` | The recipes described below |
| `tester` | `io.resys.orw:resys-orw-jsp-tester` | Renders pages with embedded Tomcat 10.1 (Jakarta EE), and the Thymeleaf templates they were migrated to with Spring MVC, for tests, with fixtures and tag mocking, to check that a migration keeps the output the same (see [Testing pages](#testing-pages-the-tester)) |
| `tester-example` | `io.resys.orw:resys-orw-jsp-tester-example` | A small Struts 1 (weblegacy 1.5, Jakarta EE) web application tested with the tester; an example and a testbed |

All are version `1.0-SNAPSHOT`, built for Java 21 against OpenRewrite 8.90.4.

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

// Cleanup: write back the files a recipe changed (or, as MaintainFixtures can, created or deleted).
RecipeRun cleanup = new RemoveUnusedTaglibs().run(new InMemoryLargeSourceSet(sources), ctx);
for (Result result : cleanup.getChangeset().getAllResults()) {
    SourceFile after = result.getAfter();
    if (after != null) {
        Path file = project.resolve(after.getSourcePath());
        Files.createDirectories(file.getParent());
        Files.writeString(file, after.printAll(), after.getCharset());
    } else {
        Files.delete(project.resolve(result.getBefore().getSourcePath()));
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

All recipes are in the `io.resys.orw.jsp.receipes` package.

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

### `ConvertScriptletsToJstl`

**This recipe changes pages.** It converts scriptlets to JSTL and EL wherever the result behaves
the same:

| Scriptlet | Becomes |
|---|---|
| `<% if (c) { %>…<% } %>` | `<c:if test="${…}">…</c:if>` |
| `if` / `else if` / `else` chains | `<c:choose>`, `<c:when>`, `<c:otherwise>` |
| `<% for (Order o : orders) { %>…<% } %>` | `<c:forEach var="o" items="${orders}">` |
| `<% for (int i = a; i < b; i++) { %>` | `<c:forEach var="i" begin="a" end="${b - 1}">` (simple counting loops only) |
| `<% x = expr; pageContext.setAttribute("x", x); %>` | `<c:set var="x" value="${…}"/>`, once no other Java code uses `x` |
| `<%= expr %>` | `${…}` |

**Run it after `MigrateJavaVariablesToPageAttributes` with `mirrorAll`.** EL can only see page
attributes, not Java variables. The two recipes together take raw scriptlets all the way to JSTL:

```java
Recipe pipeline = new CompositeRecipe(List.of(               // org.openrewrite.config.CompositeRecipe
        new MigrateJavaVariablesToPageAttributes(null, true), // mirrorAll
        new ConvertScriptletsToJstl(null, null)));
```

```jsp
<%-- before --%>
<% List orders = (List) request.getAttribute("orders"); %>
<% for (Order o : orders) { %>
  <td><%= o.getTotal() %></td>
<% } %>

<%-- after --%>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<c:set var="orders" value="${requestScope.orders}"/>
<c:forEach var="o" items="${orders}">
  <td>${o.total}</td>
</c:forEach>
```

**Java expressions → EL.** Conditions, `items`, bounds, values and outputs are translated by a
small parser that only accepts Java with an exact EL equivalent:

- getters: `a.getB()` → `a.b`;
- request parameters and attributes: `request.getParameter("p")` → `param.p`,
  `request`/`session`/`application.getAttribute("x")` → `requestScope.x` and so on;
- `.equals()` → `==`, `.isEmpty()` → `empty`, `.size()`/`.length()` → `fn:length(…)`;
- comparisons (written as `lt`/`gt`/`le`/`ge`), `&&`, `||`, `!`, `?:`, `-`, `*`, `%`, literals,
  casts (dropped) and indexing.

These are **not** translated, because EL would behave differently:

- `+`: EL can't concatenate strings;
- `/`: EL divides in floating point;
- `is…()` getters: EL only reads them for primitive `boolean` properties;
- other method calls, `new`, and `instanceof`.

In EL, `==` compares objects with `equals()` rather than by reference.

**When a construct stays Java:**

- its expression can't be translated, including when it uses a Java variable that isn't mirrored
  into a page attribute;
- the `}` that closes a block isn't in a sibling scriptlet of its own, i.e. the block crosses a
  tag boundary;
- an `if` opener has other code after its `{`;
- Java code in a loop's body still uses the loop variable.

Statements before an opener, or after a closing `}`, are kept in a smaller scriptlet. Every
construct considered is listed in the `JspScriptletConversions` data table (`sourcePath`, `line`,
`construct`, `status` = `CONVERTED`/`SKIPPED`, `detail`). `detail` holds the produced EL for a
conversion, or the reason it stayed Java.

**Taglibs.** The JSTL core library is declared if it's needed and the page doesn't already
declare it, either itself or through an included file. The functions library is declared when
`fn:length` is used. If the page declares the library under another prefix, that prefix is used.

#### Options

| Option | Type | Default | Description |
|---|---|---|---|
| `jstlVersion` | `String` | `1.2` | Selects the taglib URIs. `1.0`: `http://java.sun.com/jstl/core`, no functions library. `1.1`/`1.2`: `http://java.sun.com/jsp/jstl/core`. `3.0`: `jakarta.tags.core`. |
| `convertOutputs` | `Boolean` | `true` | Also convert `<%= %>` outputs to EL |

**Needs EL.** With JSTL 1.1 or later, nothing changes on `isELIgnored` pages, nor when a parsed
`web.xml` declares a Servlet version before 2.4 (see `MigrateJavaVariablesToPageAttributes`).
JSTL 1.0 evaluates EL itself, so with `jstlVersion: 1.0` it works even with EL disabled in the
container. Outputs are then written as `<c:out value="${…}" escapeXml="false"/>`.

### `FindUnmigratableScriptlets`

An analysis recipe that finds the Java the automated migration can't remove. That migration is
`MigrateJavaVariablesToPageAttributes` with `mirrorAll`, followed by `ConvertScriptletsToJstl`.
For each construct that stays Java, it reports why and what kind of manual work it needs. **It
only adds markers; pages are otherwise unchanged.**

It simulates that migration on each page, then checks every Java construct against the result.
Those are scriptlets, `<%= %>` expressions, `<%! %>` declarations, and `<%= %>` in custom tag
attributes. Each construct gets one of two statuses:

- **`MIGRATABLE`**: the migration removes it, so running those two recipes is enough.
- **`MANUAL`**: it stays Java. It's marked in the page
  (`~~(Manual migration (DATA_ACCESS, VARIABLE): …)~~>`) and listed with the reasons the migration
  gave, its categories, and a hint.

Run it on untouched pages to see what the migration will leave, or after the migration to list
what's left. It takes one option, `jstlVersion`, used as in `ConvertScriptletsToJstl`.

| Category | Means | Hint |
|---|---|---|
| `DECLARATION` | `<%! %>` fields and methods | Move them into a Java class (a helper bean, or the controller) |
| `DATA_ACCESS` | JDBC, JNDI, JPA, `*Dao`/`*Service`/`*Manager` calls | Move into the controller or a service; pass the results as model attributes |
| `REQUEST_SESSION_STATE` | `setAttribute`, `removeAttribute`, `invalidate` (not the migration's own mirrors) | Move into the controller |
| `RESPONSE_CONTROL` | redirects, forwards, `return;`, status codes, headers, cookies | Move into the controller |
| `OUTPUT_WRITING` | `out.print(…)` | Write it as template text |
| `EXCEPTION_HANDLING` | `try`/`catch`/`throw` | Handle errors in the controller |
| `STATIC_CALL` | `Class.method(…)` | Call it in the controller, or expose it to the view as a helper |
| `OBJECT_CREATION` | `new …` | Create the objects in the controller |
| `CONTROL_FLOW` | an `if`/loop that can't convert (the reason says why) | Make its expression expressible in EL, or compute it in the controller |
| `BLOCK_DELIMITER` | a `}` or `else` of a block that stays Java | Migrate the block it belongs to |
| `VARIABLE` | variable state kept in Java | Compute it in the controller and pass it as a model attribute |
| `EXPRESSION` | a `<%= %>` that can't become EL | Compute it in the controller, or use getters EL can read |
| `TAG_ATTRIBUTE` | `<%= %>` in a custom tag attribute; the reason shows its EL form if it has one | Migrate it together with the tag |
| `OTHER` | anything else | Rewrite as JSTL/EL, or move into the controller |

Categories are recognized by text patterns, so treat them as a guide. Results go to two data
tables:

- **`JspManualMigrations`**, one row per construct: `sourcePath`, `line`, `kind`, `status`,
  `categories`, `reasons`, `hint`, and `code` (one line, shortened).
- **`JspMigrationEffort`**, one row per page: `javaConstructs`, `migratable`, `manual`, and
  `manualJavaLines`, as a rough estimate of the manual effort.

### `MaintainFixtures`

**This recipe creates, changes and deletes files.** It maintains the [tester's](#testing-pages-the-tester)
fixtures for the JSP pages, using the inputs `FindModelAttributes` finds each page reads:

- **Creating.** A page with no fixture (no fixture whose `"page"` is that page) gets a skeleton.
  Parameters go under `parameters`, request and any-scope attributes under `request`, and session
  and application attributes under their own sections. Each value is shaped by its type and the
  properties the page reads, with placeholders to fill in: `""`, `0`, `false`, or a date.
  Anything a fixture can't express goes in a `"_todo"` list, which the tester ignores: inputs read
  by a non-literal key, Tiles attributes, Struts `DynaActionForm` form beans (Struts creates
  those), and types that aren't fully qualified.

  ```json
  {
    "page": "/WEB-INF/views/products.jsp",
    "parameters": { "page": "" },
    "request": {
      "buyer": { "@class": "com.acme.shop.Customer", "name": "" },
      "products": [ { "name": "", "price": "" } ],
      "pageCount": ""
    }
  }
  ```

  (The recipe writes one entry per line; shown compact here.)
- **Updating.** Each existing fixture for a page gets the inputs it lacks: sections, attributes,
  and nested properties, also in each element of a list. **They're added as `null`**, never as
  placeholders. A fixture may lack an input on purpose (for example, no signed-in user), and a
  `null` input is the same as an absent one, so the fixture renders exactly as before and its
  snapshot stays valid. The key is simply there to fill in. Existing values are never changed or
  removed, and a complete fixture isn't rewritten.
- **Deleting** (only with `deleteOrphans`). A fixture whose page no longer exists is deleted,
  together with its `.expected.html`.

New fixtures are named after the page path without `/WEB-INF/views/` (or `/WEB-INF/jsp/`,
`/WEB-INF/pages/`, `/WEB-INF/`) and the extension: `/WEB-INF/views/orders/list.jsp` becomes
`orders/list.json`, numbered `-2`, `-3`… if that name is taken.

#### Options

| Option | Type | Default | Description |
|---|---|---|---|
| `fixturesDirectory` | `String` | `src/test/fixtures` | Where the fixtures are, relative to the project root |
| `webappDirectory` | `String` | `src/main/webapp` | The web application root; page paths are relative to it |
| `includes` | `List<String>` | `**/*.jsp` | Pages to maintain, as patterns on the page path, e.g. `/WEB-INF/views/**`. `*` matches within a path segment, `**` across segments, `?` one character. The default leaves out `.jspf` fragments. |
| `excludes` | `List<String>` | none | Pages to leave out. They get no fixtures created or updated, and theirs are never deleted. |
| `deleteOrphans` | `Boolean` | `false` | Delete fixtures (and their expected output) whose page no longer exists |

**Parsing.** Parse every page (`.jsp`/`.jspf` under the web application) with `JspParser`: a page
that isn't parsed counts as gone. Parse the fixture files (`.json` and `.expected.html`) with
OpenRewrite's `PlainTextParser`, so the recipe can create, change and delete them. A
`struts-config.xml` parsed with `XmlParser` adds form beans' types.

```java
List<SourceFile> sources = new ArrayList<>(JspParser.builder().build().parse(jsps, project, ctx).toList());
sources.addAll(PlainTextParser.builder().build().parse(fixtureFiles, project, ctx).toList());
RecipeRun run = new MaintainFixtures(null, null, List.of("/WEB-INF/views/**"), null, true)
        .run(new InMemoryLargeSourceSet(sources), ctx);
```

Write the results back as in [Running the recipes](#running-the-recipes), including created and
deleted files. Every action is listed in the `JspFixtureChanges` data table (`fixture`, `page`,
`action`, `details`). The actions are `CREATED`, `UPDATED` (with the paths added), `DELETED`, and
`SKIPPED` (a `.json` that isn't fixture JSON with a `"page"`).

After creating skeletons, fill in their values, then run the tester with `-Dorw.tester.update=true`
to create their expected output, which also fills in their `mocks`.

### `FindJspProblems`

An analysis recipe that finds structural and readability problems. **It changes nothing.** It
reports each problem in two ways:

- **A marker in the source** (`SearchResult`), shown in OpenRewrite diffs as
  `~~(RULE: message)~~>` right where the problem starts. A problem inside an included file is
  marked on the page's `<%@ include %>` line.
- **A row in the `JspProblems` data table** (`table.io.resys.orw.jsp.receipes.JspProblems`),
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

## Testing pages: the tester

The `tester` module renders JSP pages the way the application's servlet container would, so you
can check that a migration doesn't change what a page outputs. It works in four steps:

1. Write fixtures that describe a page's inputs.
2. Render them with the original pages and save the output as each fixture's *expected output*
   (a snapshot).
3. Migrate the pages.
4. Render the fixtures again and compare against the saved snapshots.

The same fixtures are meant to check the Thymeleaf templates later. `tester-example` is a working
example.

```xml
<dependency>
  <groupId>io.resys.orw</groupId>
  <artifactId>resys-orw-jsp-tester</artifactId>
  <version>1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

```java
class OrdersPageTest {
    static final JspTester tester = JspTester.builder()
            .webapp(Path.of("src/main/webapp"))
            .build();

    @AfterAll
    static void stop() { tester.close(); }

    @TestFactory                                   // one test per src/test/fixtures/**/*.json
    Stream<DynamicTest> fixtures() { return tester.fixtureTests(Path.of("src/test/fixtures")); }

    @Test
    void programmatically() {                     // or render directly
        String html = tester.renderOk(RenderRequest.page("/WEB-INF/views/orders.jsp")
                .param("q", "boots")
                .requestAttribute("orders", List.of())
                .mock("acme:footer", MockBehavior.empty()));
    }
}
```

### How pages are rendered

- **Tomcat 10.1 (Jasper), Jakarta EE.** The tester runs embedded **Tomcat 10.1** (Servlet 6.0,
  JSP 3.1), for applications on `jakarta.servlet`, such as Struts 1 as weblegacy's
  `io.github.weblegacy:struts-*` 1.5, which targets Servlet 5.0 / JSP 3.0 and runs on 10.1 too.
  Libraries built on `javax.servlet` (Apache Struts 1.3, JSTL 1.2) can't load here. On first use
  the tester copies the web application to a temporary directory and starts Tomcat on a free
  port; `close()` stops it.
- **Container APIs must come from Tomcat.** Some libraries declare the Servlet, JSP or EL API at
  compile scope; for example, `jakarta.servlet.jsp.jstl-api` 2.0.0 pulls in `jakarta.servlet-api`
  5.0. An older API JAR on the test classpath would shadow Tomcat's own and break renders with a
  `NoSuchMethodError`. The tester checks for this at startup and names the JAR to exclude.
- **The application starts as configured.** Its `web.xml` is used as is, so Struts' `ActionServlet`
  starts with its `struts-config.xml`, and Struts tags such as `html:form` and `bean:message` work
  for real.
- **Classes come from the test classpath:** model beans, tag libraries (JSTL, Struts), and so on.
  Tag libraries are found the way Tomcat finds them.
- **The request is real.** Parameters are sent as real HTTP request parameters, and the locale as
  `Accept-Language`. Request, session and application attributes are put in place before the page
  runs.
- **A failure doesn't throw.** If a page fails to compile or throws an exception, `render` returns
  status 500 with the error. `renderOk` and fixture tests fail with that error.
- **URLs are left as written.** A test client has no session cookie, so Tomcat would otherwise add
  a random `;jsessionid=…` to every URL a page builds.

**Jasper options.** Set them with `jspOption(name, value)`. By default the tester sets
`strictQuoteEscaping=false`, because older containers accepted
`value="<%= map.get("x") %>"`, which Tomcat 8.5 and later reject.

### Mocking tag libraries

A tag library the pages declare but whose TLD can't be found is mocked automatically, for example
an in-house library whose implementation isn't on the test classpath. To mock a library that is
available, for example one that needs a database, use `mockTaglib(uri)`.

Automatic mocking also hides libraries that *should* be real but aren't found. Examples are a
missing dependency, or JSTL 3.0, which no longer declares the `http://java.sun.com/jsp/jstl/*`
URIs that older pages use. Their tags would render as placeholders and the tests would still
pass, so the tester logs a warning listing the libraries it mocks automatically. With
`autoMock(false)`, an unresolvable library is an error instead.

The tester finds every tag and EL function the pages use from a mocked library, using this
project's parser, and generates a TLD and handler classes for them. A mocked tag accepts any
attributes and any body content, scriptlets included. Its output follows its `MockBehavior`:

| Mode | Output |
|---|---|
| `PLACEHOLDER` (default) | The tag itself, with its evaluated attributes, around its body: `<acme:panel title="T">…</acme:panel>`. This makes mocks visible in snapshots. |
| `BODY` | Only its body |
| `EMPTY` | Nothing |
| `TEXT` | Fixed text instead of the tag |
| `CUSTOM` | Whatever a Java function returns for the tag (in code only, not in fixture files) |

A mock can also set `variables`, page attributes for tags that define variables. A mocked EL
function returns its call as text, e.g. `acme:upper(Ann)`.

**Where mocks are defined.** Later levels override earlier ones for the same tag:

1. **The tester**, in code, for every page and fixture: `JspTester.builder().mock(tag, behavior)`.
2. **A fixture's `mocks`**, or **a `RenderRequest`'s `mock(tag, behavior)`**.
3. **Otherwise** the tag renders as a `PLACEHOLDER`.

**Mocks written in Java.** `MockBehavior.custom(fn)` gets each use of the tag as a
`MockInvocation`: its `name`, evaluated `attributes` (with `attribute(name)` as text), rendered
`body`, and `pageContext`. It returns the output:

```java
static final ResourceBundle MESSAGES = ResourceBundle.getBundle("com.acme.shop.MessageResources");

static final JspTester tester = JspTester.builder()
        .webapp(Path.of("src/main/webapp"))
        // <acme:message key="orders.title"/> renders the real text
        .mock("acme:message", MockBehavior.custom(tag -> MESSAGES.getString(tag.attribute("key"))))
        .mock("acme:menu", MockBehavior.text("<nav>…</nav>"))
        .build();
```

A tester mock's library must be mocked, automatically or with `mockTaglib`; otherwise the tester
fails at startup rather than silently ignoring the mock. If a custom mock throws, the render fails
with a 500 naming the tag.

### Fixtures

A fixture is a JSON file describing a page's inputs. Its expected output sits next to it:
`orders.json` goes with `orders.expected.html`. You don't have to write fixtures from scratch: the
[`MaintainFixtures`](#maintainfixtures) recipe creates a skeleton for each page from the inputs
the page reads, and keeps fixtures in step as pages change.

```json
{
  "page": "/WEB-INF/views/orders.jsp",
  "method": "GET",
  "locale": "fi-FI",
  "parameters": { "q": "boots", "ids": ["1", "2"] },
  "request": {
    "orders": [
      { "@class": "com.acme.shop.Order", "id": 1001, "total": 19.90,
        "customer": { "name": "Ann", "vip": true } }
    ],
    "title": "Orders"
  },
  "session": { "user": { "@class": "com.acme.shop.Customer", "name": "Clerk" } },
  "application": {},
  "mocks": { "acme:footer": { "mode": "TEXT", "text": "<footer/>" } },
  "compare": "WHITESPACE"
}
```

- **Attribute values with `"@class"`** become instances of that class, so scriptlet casts like
  `(Order) request.getAttribute(…)` work. The other properties fill its fields, which need no
  setters, and nested objects get their types from the field types. Use `"@value"` to convert a
  single value instead: `{"@class": "java.util.Date", "@value": "2024-01-31T12:00:00Z"}`.
- **Other values:** objects become `Map`s and arrays `List`s, which EL and JSTL treat as beans and
  collections. Decimals stay exact `BigDecimal`s, so `19.90` stays `19.90`.
- **Mistakes fail clearly:** a misspelled property or a class that isn't on the test classpath
  fails with a clear message.
- **`mocks`** are keyed by tag as written on the page (`prefix:name`). They can use every mode
  except `CUSTOM`, which is Java and so only available in code.
- **`compare`:** `WHITESPACE` (default) treats any run of whitespace as one space and ignores
  whitespace between tags, since migrations shift whitespace but shouldn't change content.
  `EXACT` compares character by character. `HTML` compares parsed element trees, which is what
  `ThymeleafTester` uses (see below).
- **`template`** (optional) names the Thymeleaf template the page was migrated to, when the
  default mapping from the page path doesn't give it.

**Creating and updating expected output.** Run with `-Dorw.tester.update=true`:

```sh
mvn test -Dorw.tester.update=true
```

or set `JspTester.builder().updateFixtures(true)`. Each fixture's output is then written as its
expected output instead of being compared. **Review the generated files before committing them.**

**Updating also fills in the fixture's `mocks`.** Each mocked tag the page uses, including in
included files, gets `"prefix:name": {"mode": "PLACEHOLDER"}` if the fixture doesn't configure it
and no mock in code covers it. Tags mocked in code are skipped, because a fixture entry would
override the code. `PLACEHOLDER` is how the tag rendered anyway, so the snapshot doesn't change;
edit the entries where you want different output, then update again.

- Existing entries are never changed or removed.
- A fixture that's already complete isn't rewritten.
- When a fixture is rewritten, Jackson reformats it: two-space indents, one array element per
  line. Key order and numbers such as `19.90` are kept.

Without the flag, a fixture with no expected output fails and says how to create it, so a CI run
never accepts a new snapshot silently. A mismatch fails with expected and actual output, which
IDEs show as a diff.

### Checking the migrated Thymeleaf templates

`ThymeleafTester` renders the Thymeleaf templates the pages were migrated to, **from the same
fixtures**, and checks them against **the same expected output** the JSP pages produced. That
tests the migration directly: the template must render what the original page did.

```java
class OrdersTemplateTest {
    static final ThymeleafTester templates = ThymeleafTester.builder()
            .templates(Path.of("src/main/resources/templates"))  // default: classpath templates/
            .messages("com.acme.shop.MessageResources")          // for #{...}
            .build();

    @TestFactory
    Stream<DynamicTest> fixtures() { return templates.fixtureTests(Path.of("src/test/fixtures")); }
}
```

**Rendered as Spring MVC renders them.** Templates go through Spring's Thymeleaf integration, so
expressions use SpEL. `#{…}` comes from a `MessageSource` and depends on the fixture's locale.
`@{…}` builds links, and `th:field` works, all through Spring's MockMvc, with no server. The
fixture's inputs map to what a template sees in a Spring MVC application:

| Fixture | Template |
|---|---|
| `request` attributes | model variables: `${orders}` |
| `parameters` | `${param.q}` |
| `session` | `${session.user}` |
| `application` | `${application.version}` |
| `locale` | `#{…}`, `${#locale}` |

`ThymeleafTester` uses Spring Framework 6.2, which matches the tester's Tomcat 10.1 (Servlet 6.0);
Spring 7 needs Servlet 6.1.

**Which template.** By default a page maps to a template by dropping its `.jsp`/`.jspf` extension
and a leading `/WEB-INF/views/`, `/WEB-INF/jsp/`, `/WEB-INF/pages/` or `/WEB-INF/`: for example,
`/WEB-INF/views/orders/list.jsp` becomes `orders/list`. Change the mapping with
`templateName(page -> …)`, or set `"template": "…"` in a fixture.

**The JSP output is the reference.** Only `JspTester` writes expected output; `ThymeleafTester`
only compares, even in update mode. To change the expected output, update with the JSP pages.

**Comparison as HTML.** The same HTML written by another engine differs in form, so
`ThymeleafTester` compares as `HTML` by default (override with `comparison(…)`). Both outputs are
parsed as HTML and their element trees compared:

- attribute order, quoting, `<br>` vs `<br/>`, comments, and whitespace don't count (text is
  trimmed and runs of whitespace collapse);
- text and attribute values do count.

On a mismatch, the expected and actual trees are shown one element per line, so an IDE diff
points at the element that differs. The `HTML` comparison is also available to JSP fixtures with
`"compare": "HTML"`.

**Mocked tags.** JSP tag mocks don't apply to Thymeleaf. Where a page uses a mocked tag, the
template has whatever the tag was migrated to, such as a fragment or a dialect (add it with
`dialect(…)`). For the outputs to compare, the JSP-side mock must render what the real tag
renders, which a `MockBehavior.custom` mock in code can do. In the example, `acme:footer` is
mocked in code to render `<footer class="footer">© 2026 ACME</footer>`, which is also what its
migrated fragment `~{fragments/footer :: footer(2026)}` renders.

**Programmatic use:** `templates.renderOk(RenderRequest.page("/WEB-INF/views/orders.jsp")…)`, with
the same `RenderRequest`s as `JspTester`.

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
