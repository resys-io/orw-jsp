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
import org.apache.catalina.core.StandardContext;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Renders JSP pages of a web application with embedded Tomcat 10.1 (Jasper, jakarta.servlet), for tests.
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
    private final boolean autoMock;
    /** The tester's own mocks, by tag as written ({@code prefix:name}). */
    private final Map<String, MockBehavior> mocks;
    private static final Logger LOG = Logger.getLogger(JspTester.class.getName());

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
        this.autoMock = builder.autoMock;
        this.mocks = Map.copyOf(builder.mocks);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path webapp;
        private final Set<String> mockTaglibs = new LinkedHashSet<>();
        private boolean updateFixtures = Fixtures.updateRequested();
        private boolean autoMock = true;
        private final Map<String, MockBehavior> mocks = new LinkedHashMap<>();
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
         * Whether a tag library the pages declare but whose TLD can't be found is mocked (with a
         * warning listing them), or is an error. Defaults to {@code true}; turn it off when every
         * library should be real, so that a missing dependency (or e.g. JSTL 3.0, which no longer
         * declares the {@code http://java.sun.com/jsp/jstl/*} uris) fails instead of passing as mocks.
         */
        /**
         * Mocks a tag for every page and fixture, e.g. a menu tag whose output all tests share, or a
         * {@link MockBehavior#custom custom} mock written in Java. A request's or fixture's mock for
         * the same tag overrides it. The tag's library must be mocked (automatically, or with
         * {@link #mockTaglib}).
         *
         * @param tag the tag as the pages write it, e.g. {@code acme:message}.
         */
        public Builder mock(String tag, MockBehavior behavior) {
            if (tag.indexOf(':') <= 0) {
                throw new IllegalArgumentException("Mock '" + tag + "': expected prefix:name");
            }
            mocks.put(tag, behavior);
            return this;
        }

        public Builder autoMock(boolean autoMock) {
            this.autoMock = autoMock;
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

    /**
     * Fails fast, with the culprit, if a Servlet/JSP/EL API JAR on the test classpath shadows
     * Tomcat's own: libraries often declare these APIs at compile scope (e.g. jstl-api 2.0.0), and
     * an older copy otherwise surfaces as a NoSuchMethodError in the middle of a render.
     */
    private static void checkContainerApis() {
        Map<Class<?>, Class<?>> apiToImplementation = Map.of(
                jakarta.servlet.Servlet.class, org.apache.catalina.startup.Tomcat.class,
                jakarta.servlet.jsp.JspFactory.class, org.apache.jasper.servlet.JspServlet.class,
                jakarta.el.ExpressionFactory.class, org.apache.el.ExpressionFactoryImpl.class);
        apiToImplementation.forEach((api, implementation) -> {
            String apiJar = String.valueOf(api.getProtectionDomain().getCodeSource().getLocation());
            String tomcatJar = String.valueOf(implementation.getProtectionDomain().getCodeSource().getLocation());
            if (!apiJar.equals(tomcatJar)) {
                throw new IllegalStateException(api.getPackageName() + " is loaded from " + apiJar + ", not from " +
                                                "Tomcat's " + tomcatJar + ". Exclude that API JAR from the test " +
                                                "classpath (a dependency probably declares it at compile scope): " +
                                                "the container provides it.");
            }
        });
    }

    private void start() {
        if (tomcat != null) {
            return;
        }
        try {
            checkContainerApis();
            quietLogging();
            workDir = Files.createTempDirectory("orw-jsp-tester");
            Path docBase = workDir.resolve("webapp");
            copy(webapp, docBase);

            pages = new PageScanner().scan(docBase);
            TagLibraryResolver resolver = new TagLibraryResolver(Map.of(), ClassPath.entries(), docBase);
            List<PageScanner.Library> toMock = new ArrayList<>();
            List<String> unresolved = new ArrayList<>();
            for (PageScanner.Library library : pages.libraries.values()) {
                boolean forced = forcedMocks.contains(library.uri);
                if (forced || resolver.resolve(library.uri, library.declaringPage) == null) {
                    toMock.add(library);
                    mocked.add(library.uri);
                    if (!forced) {
                        unresolved.add(library.uri);
                    }
                }
            }
            if (!unresolved.isEmpty()) {
                // A library that should be real but isn't found (a missing dependency, or e.g. JSTL 3.0
                // with the pre-3.0 uris) would otherwise pass tests as placeholders unnoticed.
                if (!autoMock) {
                    throw new IllegalStateException("No TLD found for the tag libraries " + unresolved + " (autoMock " +
                                                    "is off): add their implementation to the test classpath, or " +
                                                    "mock them with mockTaglib(uri).");
                }
                LOG.warning("Mocking tag libraries with no TLD on the test classpath or in the web application: " +
                            unresolved + ". If one should be real, add it to the test classpath.");
            }
            checkMocks();
            MockLibraries.write(docBase, toMock);

            tomcat = new Tomcat();
            tomcat.setBaseDir(workDir.resolve("tomcat").toString());
            tomcat.setPort(0);
            tomcat.getConnector();
            Context context = tomcat.addWebapp("", docBase.toString());
            context.setParentClassLoader(JspTester.class.getClassLoader());
            if (context instanceof StandardContext) {
                // Memory leak detection on undeploy is pointless for a test server, and on Java 9+
                // it only warns that it needs --add-opens.
                StandardContext standard = (StandardContext) context;
                standard.setClearReferencesThreadLocals(false);
                standard.setClearReferencesRmiTargets(false);
            }
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
        // The tester's own mocks apply where the page declares their prefix (checked at start)...
        mocks.forEach((tag, behavior) -> {
            String uri = prefixes.get(tag.substring(0, tag.indexOf(':')));
            if (uri != null) {
                behaviors.put(MockTag.key(uri, tag.substring(tag.indexOf(':') + 1)), behavior);
            }
        });
        // ...and the request's (or fixture's) override them.
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
            behaviors.put(MockTag.key(uri, tag.substring(colon + 1)), behavior);
        });
        return behaviors;
    }

    /**
     * The mocked tags (as written, e.g. {@code acme:menu}) the request's page uses, including its
     * included files, that neither the request nor the tester configures: those render as
     * {@link MockBehavior.Mode#PLACEHOLDER}. Fixture updates add them to the fixture's {@code mocks}.
     */
    @Override
    public synchronized Set<String> unconfiguredMocks(RenderRequest request) {
        start();
        Map<String, String> prefixes = pages.taglibsByPage.getOrDefault(request.getPage(), Map.of());
        Set<String> unconfigured = new TreeSet<>();
        for (String tag : pages.tagsByPage.getOrDefault(request.getPage(), Set.of())) {
            String uri = prefixes.get(tag.substring(0, tag.indexOf(':')));
            if (uri != null && mocked.contains(uri) && !request.getMocks().containsKey(tag) && !mocks.containsKey(tag)) {
                unconfigured.add(tag);
            }
        }
        return unconfigured;
    }

    /**
     * Each of the tester's own mocks must be for a mocked library, wherever a page declares its
     * prefix: otherwise it would silently not apply.
     */
    private void checkMocks() {
        mocks.keySet().forEach(tag -> pages.taglibsByPage.forEach((page, prefixes) -> {
            String uri = prefixes.get(tag.substring(0, tag.indexOf(':')));
            if (uri != null && !mocked.contains(uri)) {
                throw new IllegalStateException("Mock '" + tag + "': its library " + uri + " (declared by " + page +
                                                ") isn't mocked: it was found. Use mockTaglib(\"" + uri +
                                                "\") to mock it anyway.");
            }
        }));
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
        // Tomcat's shutdown race ("acceptor thread did not stop cleanly") is noise for a test server.
        Logger shutdown = Logger.getLogger("org.apache.tomcat.util.net");
        shutdown.setLevel(Level.SEVERE);
        QUIET.add(shutdown);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void close() {
        try {
            if (tomcat != null) {
                tomcat.stop();
                tomcat.destroy();
            }
        } catch (LifecycleException e) {
            throw new IllegalStateException(e);
        } finally {
            tomcat = null;
            // Also after a failed start (e.g. autoMock off), which leaves the copy behind.
            deleteQuietly(workDir);
            workDir = null;
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
