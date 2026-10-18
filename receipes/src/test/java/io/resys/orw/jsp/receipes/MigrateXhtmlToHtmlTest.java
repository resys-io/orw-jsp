package io.resys.orw.jsp.receipes;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static io.resys.orw.jsp.Assertions.jsp;

class MigrateXhtmlToHtmlTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateXhtmlToHtml(null));
    }

    @Test
    void migratesXhtmlPage() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="application/xhtml+xml; charset=UTF-8" %>
                        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN"
                            "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
                        <html xmlns="http://www.w3.org/1999/xhtml">
                        <head>
                            <meta http-equiv="Content-Type" content="application/xhtml+xml; charset=UTF-8" />
                            <link rel="stylesheet" href="a.css"/>
                            <script src="a.js"/>
                        </head>
                        <body>
                        <div class="spacer"/>
                        <p>one<br/>two<br />three<br></br>four</p>
                        <img src="x.png" alt=""></img>
                        <textarea name="t"/>
                        </body>
                        </html>
                        """,
                        """
                        <%@ page contentType="text/html; charset=UTF-8" %>
                        <!DOCTYPE html>
                        <html xmlns="http://www.w3.org/1999/xhtml">
                        <head>
                            <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
                            <link rel="stylesheet" href="a.css">
                            <script src="a.js"></script>
                        </head>
                        <body>
                        <div class="spacer"></div>
                        <p>one<br>two<br>three<br>four</p>
                        <img src="x.png" alt="">
                        <textarea name="t"></textarea>
                        </body>
                        </html>
                        """
                )
        );
    }

    @Test
    void migratesDoctypes() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="application/xhtml+xml" %>
                        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
                        """,
                        """
                        <%@ page contentType="text/html" %>
                        <!DOCTYPE html>
                        """,
                        spec -> spec.path("xhtml11.jsp")
                ),
                jsp(
                        """
                        <%@ page contentType="application/xhtml+xml" %>
                        <!doctype HTML system "about:legacy-compat"><br/>
                        """,
                        """
                        <%@ page contentType="text/html" %>
                        <!DOCTYPE html><br>
                        """,
                        spec -> spec.path("system.jsp")
                ),
                jsp(
                        """
                        <%@ page contentType="application/xhtml+xml" %>
                        <!doctype html><br/>
                        """,
                        """
                        <%@ page contentType="text/html" %>
                        <!doctype html><br>
                        """,
                        spec -> spec.path("html5.jsp")
                ),
                jsp(
                        """
                        <%@ page contentType="application/xhtml+xml" %>
                        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "x.dtd" [ <!ENTITY nbsp "&#160;"> ]><br/>
                        """,
                        """
                        <%@ page contentType="text/html" %>
                        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "x.dtd" [ <!ENTITY nbsp "&#160;"> ]><br>
                        """,
                        spec -> spec.path("subset.jsp")
                ),
                jsp(
                        """
                        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "x.dtd">
                        """,
                        spec -> spec.path("html.jsp")
                )
        );
    }

    @Test
    void removesXmlDeclaration() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="application/xhtml+xml; charset=UTF-8" %>
                        <?xml version="1.0" encoding="UTF-8"?>
                        <?xml-stylesheet type="text/css" href="a.css"?>
                        <!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "x.dtd">
                        <html><br/></html>
                        """,
                        """
                        <%@ page contentType="text/html; charset=UTF-8" %>
                        <?xml-stylesheet type="text/css" href="a.css"?>
                        <!DOCTYPE html>
                        <html><br></html>
                        """
                )
        );
    }

    @Test
    void keepsXmlDeclarationWhenDisabled() {
        rewriteRun(
                spec -> spec.recipe(new MigrateXhtmlToHtml(false)),
                jsp(
                        """
                        <%@ page contentType="application/xhtml+xml" %>
                        <?xml version="1.0" encoding="UTF-8"?>
                        <html><br/></html>
                        """,
                        """
                        <%@ page contentType="text/html" %>
                        <?xml version="1.0" encoding="UTF-8"?>
                        <html><br></html>
                        """
                )
        );
    }

    @Test
    void leavesHtmlPagesAlone() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="text/html" %>
                        <p>one<br/>two</p><div/>
                        """
                ),
                jsp(
                        """
                        <p>no content type is text/html<br/></p><div/>
                        """,
                        spec -> spec.path("default.jsp")
                )
        );
    }

    @Test
    void leavesOtherXmlAlone() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="text/xml" %>
                        <feed><entry/><br/></feed>
                        """
                )
        );
    }

    @Test
    void keepsJspActionsAndSelfClosingTagsInSvg() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="application/xhtml+xml" %>
                        <%@ taglib prefix="c" uri="jakarta.tags.core" %>
                        <c:out value="${x}"/>
                        <svg width="10" height="10"><circle r="4"/><path d="M0 0"/></svg>
                        <math><mi>x</mi><mspace width="1em"/></math>
                        <svg/><br/>
                        """,
                        """
                        <%@ page contentType="text/html" %>
                        <%@ taglib prefix="c" uri="jakarta.tags.core" %>
                        <c:out value="${x}"/>
                        <svg width="10" height="10"><circle r="4"/><path d="M0 0"/></svg>
                        <math><mi>x</mi><mspace width="1em"/></math>
                        <svg/><br>
                        """
                )
        );
    }

    @Test
    void tagsContainingJsp() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="application/xhtml+xml" %>
                        <%@ taglib prefix="c" uri="jakarta.tags.core" %>
                        <input type="text" value="<%= value %>" />
                        <input type="checkbox" <c:if test="${on}">checked="checked"</c:if>/>
                        <span title="${title}" class="<%= css %>"/>
                        <c:if test="${a}"><hr/><div class="x"/></c:if>
                        """,
                        """
                        <%@ page contentType="text/html" %>
                        <%@ taglib prefix="c" uri="jakarta.tags.core" %>
                        <input type="text" value="<%= value %>">
                        <input type="checkbox" <c:if test="${on}">checked="checked"</c:if>>
                        <span title="${title}" class="<%= css %>"></span>
                        <c:if test="${a}"><hr><div class="x"></div></c:if>
                        """
                )
        );
    }

    @Test
    void leavesCommentsCdataAndScriptsAlone() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="application/xhtml+xml" %>
                        <!-- <br/> <div/> -->
                        <script>/*<![CDATA[*/ document.write("<br/><div/>"); /*]]>*/</script>
                        <style>p > span { color: red; }</style>
                        <![CDATA[ <br/> ]]>
                        <br/>
                        """,
                        """
                        <%@ page contentType="text/html" %>
                        <!-- <br/> <div/> -->
                        <script>/*<![CDATA[*/ document.write("<br/><div/>"); /*]]>*/</script>
                        <style>p > span { color: red; }</style>
                        <![CDATA[ <br/> ]]>
                        <br>
                        """
                )
        );
    }

    @Test
    void contentTypeSetFromScriptlet() {
        rewriteRun(
                jsp(
                        """
                        <% response.setContentType("application/xhtml+xml;charset=UTF-8"); %>
                        <p>a<br/></p>
                        """,
                        """
                        <% response.setContentType("text/html;charset=UTF-8"); %>
                        <p>a<br></p>
                        """
                )
        );
    }

    @Test
    void migratesFragmentsIncludedByXhtmlPages() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="application/xhtml+xml" %>
                        <%@ include file="header.jspf" %>
                        <p>page<br/></p>
                        """,
                        """
                        <%@ page contentType="text/html" %>
                        <%@ include file="header.jspf" %>
                        <p>page<br></p>
                        """,
                        spec -> spec.path("page.jsp")
                ),
                jsp(
                        """
                        <div class="header"/><hr/>
                        """,
                        """
                        <div class="header"></div><hr>
                        """,
                        spec -> spec.path("header.jspf")
                ),
                jsp(
                        """
                        <div class="unused"/><hr/>
                        """,
                        spec -> spec.path("unused.jspf")
                )
        );
    }

    @Test
    void migratesPageWhoseContentTypeIsInAnInclude() {
        rewriteRun(
                jsp(
                        """
                        <%@ include file="init.jspf" %>
                        <p>page<br/></p>
                        """,
                        """
                        <%@ include file="init.jspf" %>
                        <p>page<br></p>
                        """,
                        spec -> spec.path("page.jsp")
                ),
                jsp(
                        """
                        <%@ page contentType="application/xhtml+xml;charset=UTF-8" %>
                        """,
                        """
                        <%@ page contentType="text/html;charset=UTF-8" %>
                        """,
                        spec -> spec.path("init.jspf")
                )
        );
    }

    @Test
    void leavesFragmentAloneWhenAlsoIncludedByOtherXml() {
        rewriteRun(
                jsp(
                        """
                        <%@ page contentType="application/xhtml+xml" %>
                        <%@ include file="shared.jspf" %>
                        """,
                        """
                        <%@ page contentType="text/html" %>
                        <%@ include file="shared.jspf" %>
                        """,
                        spec -> spec.path("page.jsp")
                ),
                jsp(
                        """
                        <%@ page contentType="text/xml" %>
                        <%@ include file="shared.jspf" %>
                        """,
                        spec -> spec.path("feed.jsp")
                ),
                jsp(
                        """
                        <item/>
                        """,
                        spec -> spec.path("shared.jspf")
                )
        );
    }
}
