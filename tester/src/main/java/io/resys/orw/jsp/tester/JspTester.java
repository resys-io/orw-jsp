package io.resys.orw.jsp.tester;

import io.resys.orw.jsp.internal.TagLibraryResolver;
import io.resys.orw.jsp.tester.internal.ClassPath;
import io.resys.orw.jsp.tester.internal.MockLibraries;
import io.resys.orw.jsp.tester.internal.PageScanner;
import io.resys.orw.jsp.tester.internal.RenderServlet;
import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.DynamicTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Renders JSP pages of a web application with embedded Tomcat 9 (Jasper), for tests.
 * <pre>{@code
 * static JspTester tester = JspTester.builder()
 *         .webapp(Path.of("src/main/webapp"))
 *         .mockTaglib("http://struts.apache.org/tags-html")   // mock even though it's available
 *         .build();
 *
 * @Test void orders() { tester.verify(Path.of("src/test/fixtures/orders.json")); }
 * @AfterAll static void stop() { tester.close(); }
 * }</pre>
 * On first use, it copies the web application to a temporary directory, mocks the tag libraries it
 * must (see below), and starts Tomcat on a free port; {@link #close()} stops it.
 * <p>
 * Tag libraries are found the way Tomcat finds them: in the web application ({@code web.xml},
 * {@code WEB-INF}, {@code WEB-INF/lib}) and in JARs on the test classpath. A library declared by the
 * pages that can't be found that way is mocked, as is any library passed to
 * {@link Builder#mockTaglib}: every tag the pages use from it renders per its {@link MockBehavior}
 * (by default, the tag itself around its body), and every EL function returns its call as text.
 * <p>
 * Pages and attributes: see {@link RenderRequest}. Application classes (e.g. the model beans a
 * page casts attributes to) come from the test classpath.
 */
public final class JspTester implements Renderer, AutoCloseable {

    private static final Pattern CHARSET = Pattern.compile("charset=([^;]+)", Pattern.CASE_INSENSITIVE);
    /** Keeps Tomcat's loggers configured (JUL holds them weakly). */
    private static final List<Logger> QUIET = new ArrayList<>();

    private final Path webapp;
    private final Set<String> forcedMocks;
    private final boolean updateFixtures;
    private final Map<String, String> jspOptions;

    private Tomcat tomcat;
    private RenderServlet servlet;
    private PageScanner pages;
    private Path workDir;
    private int port;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Set<String> mocked = new LinkedHashSet<>();

    private JspTester(Builder builder) {
        this.webapp = builder.webapp.toAbsolutePath().normalize();
        this.forcedMocks = Set.copyOf(builder.mockTaglibs);
        this.updateFixtures = builder.updateFixtures;
        this.jspOptions = Map.copyOf(builder.jspOptions);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path webapp;
        private final Set<String> mockTaglibs = new LinkedHashSet<>();
        private boolean updateFixtures = Fixtures.updateRequested();
        private final Map<String, String> jspOptions = new HashMap<>(Map.of(
                // Legacy containers accepted value="<%= m.get("x") %>" (unescaped quotes inside an
                // expression attribute); Tomcat 8.5+ rejects it unless this is off.
                "strictQuoteEscaping", "false"));

        /**
         * @param webapp the web application's root directory (with {@code WEB-INF}), e.g. {@code src/main/webapp}.
         */
        public Builder webapp(Path webapp) {
            this.webapp = webapp;
            return this;
        }

        /**
         * Mocks a tag library even if its implementation is available, e.g. one that needs a running
         * framework (Struts' {@code html:} tags need its ActionServlet) or a database.
         */
        public Builder mockTaglib(String uri) {
            mockTaglibs.add(uri);
            return this;
        }

        /**
         * Sets a Jasper option (an init parameter of Tomcat's JSP servlet), e.g. {@code compilerSourceVM}
         * or {@code trimSpaces}. Defaults: {@code strictQuoteEscaping=false}, as legacy containers
         * behaved.
         */
        public Builder jspOption(String name, String value) {
            jspOptions.put(name, value);
            return this;
        }

        /**
         * Whether {@link #verify} writes the actual output as the expected output instead of
         * comparing. Defaults to the {@value Fixtures#UPDATE_PROPERTY} system property.
         */
        public Builder updateFixtures(boolean updateFixtures) {
            this.updateFixtures = updateFixtures;
            return this;
        }

        public JspTester build() {
            if (webapp == null) {
                throw new IllegalStateException("webapp(...) is required");
            }
            return new JspTester(this);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Rendering
    // -----------------------------------------------------------------------------------------

    @Override
    public synchronized Rendered render(RenderRequest request) {
        start();
        String id = UUID.randomUUID().toString();
        servlet.register(id, new RenderServlet.Pending(request, behaviors(request)));
        try {
            StringBuilder query = new StringBuilder();
            request.getParameters().forEach((name, values) -> values.forEach(value ->
                    query.append(query.isEmpty() ? "" : "&").append(encode(name)).append('=').append(encode(value))));
            URI uri = URI.create("http://localhost:" + port + RenderServlet.PATH);
            HttpRequest.Builder http = HttpRequest.newBuilder()
                    .header(RenderServlet.HEADER, id)
                    .header("Accept-Language", request.getLocale().toLanguageTag());
            if ("GET".equals(request.getMethod())) {
                http.uri(query.isEmpty() ? uri : URI.create(uri + "?" + query)).GET();
            } else {
                http.uri(uri).header("Content-Type", "application/x-www-form-urlencoded")
                        .method(request.getMethod(), HttpRequest.BodyPublishers.ofString(query.toString()));
            }
            HttpResponse<byte[]> response = this.http.send(http.build(), HttpResponse.BodyHandlers.ofByteArray());
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            Matcher charset = CHARSET.matcher(contentType);
            // A page without a contentType directive is ISO-8859-1, as the JSP spec says.
            Charset encoding = charset.find() ? Charset.forName(charset.group(1).trim()) : StandardCharsets.ISO_8859_1;
            return new Rendered(response.statusCode(), contentType, new String(response.body(), encoding));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } finally {
            servlet.unregister(id);
        }
    }

    /**
     * Renders the request and fails unless the page rendered without error.
     */
    public String renderOk(RenderRequest request) {
        Rendered rendered = render(request);
        if (!rendered.ok()) {
            throw new AssertionError("Rendering " + request.getPage() + " failed with status " + rendered.status() +
                                     ":\n" + rendered.body());
        }
        return rendered.body();
    }

    /**
     * Renders a fixture and compares the output with its expected output; see {@link Fixtures}.
     */
    public void verify(Path fixture) {
        Fixtures.verify(this, fixture, updateFixtures);
    }

    /**
     * A dynamic test per fixture ({@code *.json}) in a directory, for a JUnit {@code @TestFactory}.
     */
    public Stream<DynamicTest> fixtureTests(Path directory) {
        return Fixtures.dynamicTests(this, directory, updateFixtures);
    }

    /**
     * The tag libraries being mocked (after the first render).
     */
    public Set<String> getMockedTaglibs() {
        return Set.copyOf(mocked);
    }

    // -----------------------------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------------------------

    private void start() {
        if (tomcat != null) {
            return;
        }
        try {
            quietLogging();
            workDir = Files.createTempDirectory("orw-jsp-tester");
            Path docBase = workDir.resolve("webapp");
            copy(webapp, docBase);

            pages = new PageScanner().scan(docBase);
            TagLibraryResolver resolver = new TagLibraryResolver(Map.of(), ClassPath.entries(), docBase);
            List<PageScanner.Library> toMock = new ArrayList<>();
            for (PageScanner.Library library : pages.libraries.values()) {
                if (forcedMocks.contains(library.uri) || resolver.resolve(library.uri, library.declaringPage) == null) {
                    toMock.add(library);
                    mocked.add(library.uri);
                }
            }
            MockLibraries.write(docBase, toMock);

            tomcat = new Tomcat();
            tomcat.setBaseDir(workDir.resolve("tomcat").toString());
            tomcat.setPort(0);
            tomcat.getConnector();
            Context context = tomcat.addWebapp("", docBase.toString());
            context.setParentClassLoader(JspTester.class.getClassLoader());
            // The JSP servlet exists once the default web.xml settings are applied (before start).
            context.addLifecycleListener(event -> {
                if (Lifecycle.CONFIGURE_START_EVENT.equals(event.getType())) {
                    Wrapper jsp = (Wrapper) context.findChild("jsp");
                    jspOptions.forEach(jsp::addInitParameter);
                }
            });
            servlet = new RenderServlet();
            Tomcat.addServlet(context, "orw-render", servlet);
            context.addServletMappingDecoded(RenderServlet.PATH, "orw-render");
            tomcat.start();
            port = tomcat.getConnector().getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (LifecycleException e) {
            throw new IllegalStateException("Starting Tomcat failed", e);
        }
    }

    /**
     * Resolves a request's mock behaviors, keyed by tag as written on the page ({@code prefix:name}),
     * to the uri-based keys the mock tags look up.
     */
    private Map<String, MockBehavior> behaviors(RenderRequest request) {
        Map<String, MockBehavior> behaviors = new HashMap<>();
        Map<String, String> prefixes = pages.taglibsByPage.getOrDefault(request.getPage(), Map.of());
        request.getMocks().forEach((tag, behavior) -> {
            int colon = tag.indexOf(':');
            String uri = colon < 0 ? null : prefixes.get(tag.substring(0, colon));
            if (uri == null) {
                throw new IllegalArgumentException("Mock '" + tag + "': " + request.getPage() +
                                                   " declares no taglib with that prefix");
            }
            if (!mocked.contains(uri)) {
                throw new IllegalArgumentException("Mock '" + tag + "': its library " + uri + " isn't mocked " +
                                                   "(it was found; use mockTaglib to mock it anyway)");
            }
            behaviors.put(io.resys.orw.jsp.tester.MockTag.key(uri, tag.substring(colon + 1)), behavior);
        });
        return behaviors;
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path source : files.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target);
                }
            }
        }
    }

    private static void quietLogging() {
        for (String name : List.of("org.apache.catalina", "org.apache.coyote", "org.apache.tomcat", "org.apache.jasper")) {
            Logger logger = Logger.getLogger(name);
            logger.setLevel(Level.WARNING);
            QUIET.add(logger);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void close() {
        if (tomcat != null) {
            try {
                tomcat.stop();
                tomcat.destroy();
            } catch (LifecycleException e) {
                throw new IllegalStateException(e);
            } finally {
                tomcat = null;
                deleteQuietly(workDir);
            }
        }
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
            // Best effort.
        }
    }
}
