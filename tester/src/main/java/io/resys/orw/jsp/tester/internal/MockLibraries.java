package io.resys.orw.jsp.tester.internal;

import io.resys.orw.jsp.tester.MockTag;
import org.eclipse.jdt.core.compiler.batch.BatchCompiler;

import javax.servlet.Servlet;
import javax.servlet.jsp.tagext.BodyTagSupport;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Writes mock implementations of tag libraries into a (copied) web application: a TLD per library,
 * mapping each tag the pages use to a generated {@link MockTag} subclass and each EL function to a
 * generated static method, compiled into {@code WEB-INF/classes}.
 */
public final class MockLibraries {

    private static final String PACKAGE = "orw.mock";
    private static final Pattern TLD_URI = Pattern.compile("<uri>\\s*([^<]*?)\\s*</uri>");

    private MockLibraries() {
    }

    public static void write(Path webapp, List<PageScanner.Library> libraries) throws IOException {
        if (libraries.isEmpty()) {
            return;
        }
        Path sources = Files.createTempDirectory("orw-mock-src");
        Path classes = webapp.resolve("WEB-INF/classes");
        Files.createDirectories(classes);
        List<Path> sourceFiles = new ArrayList<>();
        int n = 0;
        for (PageScanner.Library library : libraries) {
            n++;
            removeRealTlds(webapp, library.uri);
            StringBuilder tld = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <taglib xmlns="http://java.sun.com/xml/ns/javaee"
                            xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                            xsi:schemaLocation="http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-jsptaglibrary_2_1.xsd"
                            version="2.1">
                        <tlib-version>1.0</tlib-version>
                        <short-name>orw-mock</short-name>
                    """);
            tld.append("    <uri>").append(xml(library.uri)).append("</uri>\n");
            int t = 0;
            for (String tag : library.tags.keySet()) {
                String className = "Mock_" + n + "_" + (++t);
                sourceFiles.add(write(sources, className, """
                        package %s;
                        public final class %s extends %s {
                            public %s() { super(%s, %s, %s); }
                        }
                        """.formatted(PACKAGE, className, MockTag.class.getName(), className,
                        java(library.uri), java(tag), java(library.prefix))));
                tld.append("""
                            <tag>
                                <name>%s</name>
                                <tag-class>%s.%s</tag-class>
                                <body-content>JSP</body-content>
                                <dynamic-attributes>true</dynamic-attributes>
                            </tag>
                        """.formatted(xml(tag), PACKAGE, className));
            }
            if (!library.functions.isEmpty()) {
                String className = "MockFunctions_" + n;
                StringBuilder source = new StringBuilder("package " + PACKAGE + ";\npublic final class " + className + " {\n");
                for (Map.Entry<String, Integer> function : library.functions.entrySet()) {
                    List<String> parameters = new ArrayList<>();
                    List<String> arguments = new ArrayList<>();
                    List<String> types = new ArrayList<>();
                    for (int a = 0; a < function.getValue(); a++) {
                        parameters.add("Object a" + a);
                        arguments.add("a" + a);
                        types.add("java.lang.Object");
                    }
                    source.append("    public static Object f_").append(function.getKey()).append('(')
                            .append(String.join(", ", parameters)).append(") { return ")
                            .append(MockTag.class.getName()).append(".function(").append(java(library.prefix))
                            .append(", ").append(java(function.getKey()))
                            .append(arguments.isEmpty() ? "" : ", " + String.join(", ", arguments)).append("); }\n");
                    tld.append("""
                                <function>
                                    <name>%s</name>
                                    <function-class>%s.%s</function-class>
                                    <function-signature>java.lang.Object f_%s(%s)</function-signature>
                                </function>
                            """.formatted(xml(function.getKey()), PACKAGE, className, function.getKey(),
                            String.join(", ", types)));
                }
                sourceFiles.add(write(sources, className, source.append("}\n").toString()));
            }
            tld.append("</taglib>\n");
            Files.writeString(tldPath(webapp, library.uri, n), tld.toString());
        }
        compile(sourceFiles, classes);
    }

    /**
     * A uri that is itself a path to a TLD is opened directly by that path; any other is matched by
     * the {@code <uri>} of the TLDs in WEB-INF (which take precedence over those in JARs).
     */
    private static Path tldPath(Path webapp, String uri, int n) throws IOException {
        Path path = !uri.contains(":") && uri.endsWith(".tld") ?
                webapp.resolve(uri.startsWith("/") ? uri.substring(1) : uri) :
                webapp.resolve("WEB-INF/orw-mocks/mock-" + n + ".tld");
        Files.createDirectories(path.getParent());
        return path;
    }

    /**
     * Removes the web application's own TLDs for a library being mocked anyway, so the mock is the
     * one Tomcat finds.
     */
    private static void removeRealTlds(Path webapp, String uri) throws IOException {
        Path webInf = webapp.resolve("WEB-INF");
        if (!Files.isDirectory(webInf)) {
            return;
        }
        List<Path> tlds;
        try (Stream<Path> files = Files.walk(webInf)) {
            tlds = files.filter(f -> f.toString().endsWith(".tld")).toList();
        }
        for (Path tld : tlds) {
            Matcher m = TLD_URI.matcher(Files.readString(tld));
            if (m.find() && m.group(1).equals(uri)) {
                Files.delete(tld);
            }
        }
    }

    private static Path write(Path sources, String className, String source) throws IOException {
        Path file = sources.resolve(PACKAGE.replace('.', '/')).resolve(className + ".java");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, source);
    }

    /**
     * Compiles with the JDK's compiler, or (on a JRE) with the Eclipse compiler Jasper brings.
     */
    private static void compile(List<Path> sourceFiles, Path classes) {
        String classPath = String.join(File.pathSeparator, location(MockTag.class), location(BodyTagSupport.class),
                location(Servlet.class));
        StringWriter errors = new StringWriter();
        boolean ok;
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        if (javac != null) {
            List<String> arguments = new ArrayList<>(List.of("-nowarn", "-d", classes.toString(), "-cp", classPath));
            sourceFiles.forEach(f -> arguments.add(f.toString()));
            ok = javac.run(null, null, new PrintStream(new WriterOutputStream(errors), true),
                    arguments.toArray(String[]::new)) == 0;
        } else {
            List<String> arguments = new ArrayList<>(List.of("-11", "-nowarn", "-d", classes.toString(), "-cp", classPath));
            sourceFiles.forEach(f -> arguments.add(f.toString()));
            ok = BatchCompiler.compile(arguments.toArray(String[]::new), new PrintWriter(new StringWriter()),
                    new PrintWriter(errors), null);
        }
        if (!ok) {
            throw new IllegalStateException("Compiling the tag mocks failed:\n" + errors);
        }
    }

    /** Lets javac report into a {@link StringWriter}. */
    private static final class WriterOutputStream extends java.io.OutputStream {
        private final StringWriter writer;

        WriterOutputStream(StringWriter writer) {
            this.writer = writer;
        }

        @Override
        public void write(int b) {
            writer.write(b);
        }
    }

    private static String location(Class<?> type) {
        try {
            return Paths.get(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String java(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
