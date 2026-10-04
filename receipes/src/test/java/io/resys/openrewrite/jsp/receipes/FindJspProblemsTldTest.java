package io.resys.openrewrite.jsp.receipes;

import io.resys.openrewrite.jsp.JspParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.resys.openrewrite.jsp.Assertions.jsp;

/**
 * {@link FindJspProblems} rules that need the tag library descriptor (TLD) of a taglib.
 */
class FindJspProblemsTldTest implements RewriteTest {

    private static final String TLD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <taglib xmlns="https://jakarta.ee/xml/ns/jakartaee" version="3.0">
                <tlib-version>1.0</tlib-version>
                <short-name>app</short-name>
                <uri>http://example.com/app</uri>
                <tag>
                    <name>greet</name>
                    <tag-class>app.GreetTag</tag-class>
                    <body-content>empty</body-content>
                    <variable>
                        <name-from-attribute>var</name-from-attribute>
                        <scope>AT_END</scope>
                    </variable>
                    <attribute><name>name</name><required>true</required></attribute>
                    <attribute><name>var</name></attribute>
                </tag>
                <tag>
                    <name>panel</name>
                    <tag-class>app.PanelTag</tag-class>
                    <body-content>scriptless</body-content>
                    <attribute><name>title</name></attribute>
                </tag>
                <tag>
                    <name>loop</name>
                    <tag-class>app.LoopTag</tag-class>
                    <body-content>JSP</body-content>
                    <variable><name-given>item</name-given><scope>NESTED</scope></variable>
                </tag>
                <tag>
                    <name>counter</name>
                    <tag-class>app.CounterTag</tag-class>
                    <body-content>empty</body-content>
                    <variable><name-given>count</name-given><scope>AT_BEGIN</scope></variable>
                </tag>
                <tag>
                    <name>box</name>
                    <tag-class>app.BoxTag</tag-class>
                    <body-content>JSP</body-content>
                    <dynamic-attributes>true</dynamic-attributes>
                </tag>
                <function>
                    <name>upper</name>
                    <function-class>app.Functions</function-class>
                    <function-signature>java.lang.String upper(java.lang.String)</function-signature>
                </function>
            </taglib>
            """;

    private static final String TAGLIB = "<%@ taglib prefix=\"a\" uri=\"http://example.com/app\" %>";

    @TempDir
    Path dir;

    private Path tld;

    @BeforeEach
    void writeTld() throws IOException {
        tld = Files.writeString(dir.resolve("app.tld"), TLD);
    }

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindJspProblems(null, null))
                .parser(JspParser.builder().taglib("http://example.com/app", tld));
    }

    @Test
    void validUsageHasNoProblems() {
        rewriteRun(
                jsp(
                        TAGLIB + """

                        <a:greet name="world"/>
                        <a:greet><jsp:attribute name="name">world</jsp:attribute></a:greet>
                        <a:panel title="t">${a:upper(user.name)}</a:panel>
                        <a:box anything="goes" data-x="1">content</a:box>
                        <c:if test="${x}">unresolved libraries are not checked: <c:whatever foo="bar"/></c:if>
                        """
                )
        );
    }

    @Test
    void unknownTagSuggestsClosestName() {
        rewriteRun(
                jsp(
                        TAGLIB + """

                        <a:gret name="world"/>
                        """,
                        TAGLIB + """

                        ~~(UNKNOWN_TAG: <a:gret> is not defined in tag library 'http://example.com/app'; did you mean 'greet'?)~~><a:gret name="world"/>
                        """
                )
        );
    }

    @Test
    void unknownAndMissingRequiredAttributes() {
        rewriteRun(
                jsp(
                        TAGLIB + """

                        <a:greet nmae="world"/>
                        """,
                        TAGLIB + """

                        ~~(UNKNOWN_ATTRIBUTE: <a:greet> has no attribute 'nmae'; did you mean 'name'?; MISSING_REQUIRED_ATTRIBUTE: <a:greet> is missing its required attribute 'name')~~><a:greet nmae="world"/>
                        """
                )
        );
    }

    @Test
    void invalidTagBodies() {
        rewriteRun(
                jsp(
                        TAGLIB + """

                        <a:greet name="world">text</a:greet>
                        <a:panel><% doSomething(); %></a:panel>
                        """,
                        TAGLIB + """

                        ~~(INVALID_TAG_BODY: <a:greet> must have an empty body (its TLD declares body-content 'empty'))~~><a:greet name="world">text</a:greet>
                        ~~(INVALID_TAG_BODY: <a:panel> must not contain scriptlets, declarations, or <%= %> expressions (its TLD declares body-content 'scriptless'))~~><a:panel><% doSomething(); %></a:panel>
                        """
                )
        );
    }

    @Test
    void unknownElFunction() {
        rewriteRun(
                jsp(
                        TAGLIB + """

                        <p>${a:uper(name)}</p>
                        """,
                        TAGLIB + """

                        <p>~~(UNKNOWN_EL_FUNCTION: EL function a:uper() is not defined in tag library 'http://example.com/app'; did you mean 'upper'?)~~>${a:uper(name)}</p>
                        """
                )
        );
    }

    @Test
    void tagVariablesComeFromTheTld() {
        // count (AT_BEGIN) and greeting (AT_END, named by var=) outlive their tags; item is NESTED.
        rewriteRun(
                jsp(
                        """
                        <%@ include file="init.jspf" %>
                        <p>${item}</p>
                        <p>${count}</p>
                        <p><%= greeting %></p>
                        """,
                        """
                        <%@ include file="init.jspf" %>
                        <p>${item}</p>
                        <p>~~(VARIABLE_FROM_INCLUDE: 'count' is a variable declared by a tag's TLD defined in included file init.jspf line 2: the page depends on a variable it does not define itself, so it can't be read or changed on its own; define the variable in the page, or pass it explicitly (e.g. as a request attribute set before the include))~~>${count}</p>
                        <p>~~(VARIABLE_FROM_INCLUDE: 'greeting' is a variable declared by a tag's TLD defined in included file init.jspf line 3: the page depends on a variable it does not define itself, so it can't be read or changed on its own; define the variable in the page, or pass it explicitly (e.g. as a request attribute set before the include))~~><%= greeting %></p>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp(
                        TAGLIB + """

                        <a:counter/>
                        <a:greet name="world" var="greeting"/>
                        <a:loop>${item}</a:loop>
                        """,
                        spec -> spec.path("init.jspf")
                )
        );
    }

    @Test
    void misuseInIncludedFileUsingAPageLevelTaglibIsReportedOnThePage() {
        // header.jspf doesn't declare the taglib itself, so only the including page can check it.
        rewriteRun(
                jsp(
                        TAGLIB + """

                        <%@ include file="header.jspf" %>
                        """,
                        TAGLIB + """

                        ~~(UNKNOWN_TAG: <a:gret> is not defined in tag library 'http://example.com/app'; did you mean 'greet'? (in header.jspf))~~><%@ include file="header.jspf" %>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp("<a:gret name=\"world\"/>", spec -> spec.path("header.jspf"))
        );
    }
}
