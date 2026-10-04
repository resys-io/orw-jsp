package io.resys.openrewrite.jsp;

import io.resys.openrewrite.jsp.tree.Jsp;
import io.resys.openrewrite.jsp.tree.TagLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.ParseWarning;
import org.openrewrite.test.RewriteTest;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.resys.openrewrite.jsp.Assertions.jsp;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolution of {@code <%@ taglib uri="..." %>} directives to their tag library descriptors.
 */
class JspTagLibraryTest implements RewriteTest {

    static String tld(String uri) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <taglib xmlns="https://jakarta.ee/xml/ns/jakartaee" version="3.0">
                    <tlib-version>1.0</tlib-version>
                    <short-name>app</short-name>
                    <uri>%s</uri>
                    <tag>
                        <name>greet</name>
                        <tag-class>app.GreetTag</tag-class>
                        <body-content>empty</body-content>
                        <variable>
                            <name-from-attribute>var</name-from-attribute>
                            <scope>AT_END</scope>
                        </variable>
                        <attribute>
                            <name>name</name>
                            <required>true</required>
                        </attribute>
                        <attribute>
                            <name>var</name>
                        </attribute>
                    </tag>
                    <function>
                        <name>upper</name>
                        <function-class>app.Functions</function-class>
                        <function-signature>java.lang.String upper(java.lang.String)</function-signature>
                    </function>
                </taglib>
                """.formatted(uri);
    }

    static Path write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    static Path jar(Path file, String entry, String content) throws IOException {
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return file;
    }

    static TagLibrary library(Jsp.Document document) {
        Jsp.Directive taglib = (Jsp.Directive) document.getNodes().get(0);
        assertThat(taglib.getMarkers().findFirst(TagLibrary.class)).as("TagLibrary marker").isPresent();
        return taglib.getMarkers().findFirst(TagLibrary.class).get();
    }

    @Test
    void explicitMappingToTldFile(@TempDir Path dir) throws IOException {
        Path tld = write(dir.resolve("tlds/app.tld"), tld("http://example.com/other-uri"));
        rewriteRun(
                spec -> spec.parser(JspParser.builder().taglib("http://example.com/app", tld)),
                jsp(
                        "<%@ taglib prefix=\"a\" uri=\"http://example.com/app\" %>",
                        spec -> spec.afterRecipe(document -> {
                            TagLibrary library = library(document);
                            assertThat(library.getUri()).isEqualTo("http://example.com/app");
                            assertThat(library.getShortName()).isEqualTo("app");
                            TagLibrary.TagDescriptor greet = library.findTag("greet");
                            assertThat(greet).isNotNull();
                            assertThat(greet.getBodyContent()).isEqualTo("empty");
                            assertThat(greet.findAttribute("name").isRequired()).isTrue();
                            assertThat(greet.findAttribute("var").isRequired()).isFalse();
                            assertThat(greet.getVariables()).singleElement().satisfies(v -> {
                                assertThat(v.getNameFromAttribute()).isEqualTo("var");
                                assertThat(v.getScope()).isEqualTo("AT_END");
                            });
                            assertThat(library.findFunction("upper").getSignature())
                                    .isEqualTo("java.lang.String upper(java.lang.String)");
                        })
                )
        );
    }

    @Test
    void explicitMappingToJar(@TempDir Path dir) throws IOException {
        Path jar = jar(dir.resolve("lib/app.jar"), "META-INF/app.tld", tld("http://example.com/app"));
        rewriteRun(
                spec -> spec.parser(JspParser.builder().taglib("http://example.com/app", jar)),
                jsp(
                        "<%@ taglib prefix=\"a\" uri=\"http://example.com/app\" %>",
                        spec -> spec.afterRecipe(document ->
                                assertThat(library(document).getSource()).endsWith("app.jar!/META-INF/app.tld"))
                )
        );
    }

    @Test
    void searchPathMatchesTldByItsUri(@TempDir Path dir) throws IOException {
        Path jar = jar(dir.resolve("lib/jstl.jar"), "META-INF/c.tld", tld("jakarta.tags.core"));
        rewriteRun(
                spec -> spec.parser(JspParser.builder().tldSearchPath(dir.resolve("lib"))),
                jsp(
                        "<%@ taglib prefix=\"c\" uri=\"jakarta.tags.core\" %>",
                        spec -> spec.afterRecipe(document ->
                                assertThat(library(document).findTag("greet")).isNotNull())
                )
        );
    }

    @Test
    void discoveredUnderWebInf(@TempDir Path dir) throws IOException {
        write(dir.resolve("webapp/WEB-INF/tlds/app.tld"), tld("http://example.com/app"));
        rewriteRun(
                jsp(
                        "<%@ taglib prefix=\"a\" uri=\"http://example.com/app\" %>",
                        spec -> spec.path(dir.resolve("webapp/pages/index.jsp")).afterRecipe(document ->
                                assertThat(library(document).getSource()).endsWith("WEB-INF/tlds/app.tld"))
                )
        );
    }

    @Test
    void discoveredInWebInfLibJar(@TempDir Path dir) throws IOException {
        jar(dir.resolve("webapp/WEB-INF/lib/app.jar"), "META-INF/app.tld", tld("http://example.com/app"));
        rewriteRun(
                jsp(
                        "<%@ taglib prefix=\"a\" uri=\"http://example.com/app\" %>",
                        spec -> spec.path(dir.resolve("webapp/index.jsp")).afterRecipe(document ->
                                assertThat(library(document).getSource()).endsWith("app.jar!/META-INF/app.tld"))
                )
        );
    }

    @Test
    void mappedInWebXml(@TempDir Path dir) throws IOException {
        write(dir.resolve("webapp/WEB-INF/app.tld"), tld("http://example.com/declared-uri"));
        write(dir.resolve("webapp/WEB-INF/web.xml"), """
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">
                    <jsp-config>
                        <taglib>
                            <taglib-uri>/app</taglib-uri>
                            <taglib-location>/WEB-INF/app.tld</taglib-location>
                        </taglib>
                    </jsp-config>
                </web-app>
                """);
        rewriteRun(
                jsp(
                        "<%@ taglib prefix=\"a\" uri=\"urn:app\" %>",
                        spec -> spec.path(dir.resolve("webapp/index.jsp")).afterRecipe(document -> {
                            // "urn:app" is mapped nowhere: no marker.
                            Jsp.Directive taglib = (Jsp.Directive) document.getNodes().get(0);
                            assertThat(taglib.getMarkers().findFirst(TagLibrary.class)).isEmpty();
                        })
                ),
                jsp(
                        "<%@ taglib prefix=\"a\" uri=\"/app\" %>",
                        spec -> spec.path(dir.resolve("webapp/other.jsp")).afterRecipe(document ->
                                assertThat(library(document).getSource()).endsWith("WEB-INF/app.tld"))
                )
        );
    }

    @Test
    void uriThatIsAPathToTheTld(@TempDir Path dir) throws IOException {
        write(dir.resolve("webapp/WEB-INF/app.tld"), tld("http://example.com/app"));
        rewriteRun(
                jsp(
                        "<%@ taglib prefix=\"a\" uri=\"/WEB-INF/app.tld\" %>",
                        spec -> spec.path(dir.resolve("webapp/pages/index.jsp")).afterRecipe(document ->
                                assertThat(library(document).findTag("greet")).isNotNull())
                )
        );
    }

    @Test
    void explicitMappingTakesPrecedence(@TempDir Path dir) throws IOException {
        write(dir.resolve("webapp/WEB-INF/app.tld"), tld("http://example.com/app"));
        Path override = write(dir.resolve("override/app.tld"), tld("http://example.com/app"));
        rewriteRun(
                spec -> spec.parser(JspParser.builder().taglib("http://example.com/app", override)),
                jsp(
                        "<%@ taglib prefix=\"a\" uri=\"http://example.com/app\" %>",
                        spec -> spec.path(dir.resolve("webapp/index.jsp")).afterRecipe(document ->
                                assertThat(library(document).getSource()).endsWith("override/app.tld"))
                )
        );
    }

    @Test
    void legacyJsp11TldWithDoctype(@TempDir Path dir) throws IOException {
        // The DOCTYPE's DTD URL must never be fetched.
        Path tld = write(dir.resolve("legacy.tld"), """
                <?xml version="1.0" encoding="ISO-8859-1" ?>
                <!DOCTYPE taglib PUBLIC "-//Sun Microsystems, Inc.//DTD JSP Tag Library 1.1//EN"
                        "http://java.sun.com/j2ee/dtds/web-jsptaglibrary_1_1.dtd">
                <taglib>
                    <tlibversion>1.0</tlibversion>
                    <shortname>legacy</shortname>
                    <tag>
                        <name>old</name>
                        <tagclass>legacy.OldTag</tagclass>
                        <bodycontent>empty</bodycontent>
                    </tag>
                </taglib>
                """);
        rewriteRun(
                spec -> spec.parser(JspParser.builder().taglib("legacy", tld)),
                jsp(
                        "<%@ taglib prefix=\"l\" uri=\"legacy\" %>",
                        spec -> spec.afterRecipe(document -> {
                            TagLibrary library = library(document);
                            assertThat(library.getShortName()).isEqualTo("legacy");
                            assertThat(library.findTag("old").getBodyContent()).isEqualTo("empty");
                        })
                )
        );
    }

    @Test
    void unresolvedTaglibHasNoMarkerAndNoWarning() {
        rewriteRun(
                jsp(
                        "<%@ taglib prefix=\"c\" uri=\"jakarta.tags.core\" %>",
                        spec -> spec.afterRecipe(document -> {
                            Jsp.Directive taglib = (Jsp.Directive) document.getNodes().get(0);
                            assertThat(taglib.getMarkers().findFirst(TagLibrary.class)).isEmpty();
                            assertThat(taglib.getMarkers().findFirst(ParseWarning.class)).isEmpty();
                        })
                )
        );
    }
}
