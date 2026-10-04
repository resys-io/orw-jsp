package io.resys.orw.jsp.tester;

import io.resys.orw.jsp.tester.internal.ThymeleafRenderController;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.dialect.IDialect;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.AbstractConfigurableTemplateResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.thymeleaf.templateresolver.FileTemplateResolver;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Renders Thymeleaf templates as a Spring MVC application does - Spring's Thymeleaf integration
 * (SpEL expressions), {@code #{...}} messages from a {@code MessageSource}, {@code @{...}} links,
 * {@code th:field} - from the same {@link RenderRequest}s and fixtures as {@link JspTester}, to
 * check that templates migrated from JSP pages render what the pages did.
 * <pre>{@code
 * static ThymeleafTester templates = ThymeleafTester.builder()
 *         .templates(Path.of("src/main/resources/templates"))
 *         .messages("com.acme.shop.MessageResources")
 *         .build();
 *
 * @TestFactory Stream<DynamicTest> migrated() { return templates.fixtureTests(Path.of("src/test/fixtures")); }
 * }</pre>
 * Rendering goes through Spring's MockMvc (no server): a request with the fixture's parameters,
 * request attributes (which, as in Spring MVC, the template sees as model variables), session
 * attributes ({@code ${session.x}}), application attributes ({@code ${application.x}}), and locale,
 * handled by a controller that returns the template. A fixture's page maps to a template by
 * {@link Builder#templateName} (by default {@code /WEB-INF/views/orders.jsp} to {@code orders}), or
 * by its {@code "template"}. Tag mocks don't apply.
 * <p>
 * Fixtures compare against the expected output JspTester wrote; this renderer never writes it, and
 * compares as {@link Comparison#HTML} unless told otherwise, since the same HTML written by another
 * engine differs in form (attribute order, quoting, whitespace).
 */
public final class ThymeleafTester implements Renderer {

    private final MockMvc mvc;
    private final Function<String, String> templateName;
    private final Comparison comparison;

    private ThymeleafTester(Builder builder) {
        AbstractConfigurableTemplateResolver resolver;
        if (builder.templateDirectory != null) {
            resolver = new FileTemplateResolver();
            resolver.setPrefix(builder.templateDirectory.toAbsolutePath().normalize() + "/");
        } else {
            resolver = new ClassLoaderTemplateResolver();
            resolver.setPrefix(builder.classpathPrefix);
        }
        resolver.setSuffix(builder.suffix);
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        builder.dialects.forEach(engine::addDialect);
        if (!builder.messageBasenames.isEmpty()) {
            ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
            messages.setBasenames(builder.messageBasenames.toArray(String[]::new));
            messages.setDefaultEncoding("UTF-8");
            messages.setFallbackToSystemLocale(false);
            engine.setTemplateEngineMessageSource(messages);
        }

        ThymeleafViewResolver views = new ThymeleafViewResolver();
        views.setTemplateEngine(engine);
        views.setCharacterEncoding("UTF-8");

        this.mvc = MockMvcBuilders.standaloneSetup(new ThymeleafRenderController()).setViewResolvers(views).build();
        this.templateName = builder.templateName;
        this.comparison = builder.comparison;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path templateDirectory;
        private String classpathPrefix = "templates/";
        private String suffix = ".html";
        private final List<String> messageBasenames = new ArrayList<>();
        private final List<IDialect> dialects = new ArrayList<>();
        private Function<String, String> templateName = ThymeleafTester::defaultTemplateName;
        private Comparison comparison = Comparison.HTML;

        /**
         * Templates from a directory, e.g. {@code src/main/resources/templates}. By default they come
         * from the classpath, under {@code templates/}, as in Spring Boot.
         */
        public Builder templates(Path directory) {
            this.templateDirectory = directory;
            return this;
        }

        /**
         * Templates from the classpath, under a prefix such as {@code templates/}.
         */
        public Builder templatesOnClasspath(String prefix) {
            this.templateDirectory = null;
            this.classpathPrefix = prefix.endsWith("/") || prefix.isEmpty() ? prefix : prefix + "/";
            return this;
        }

        /** The template files' suffix; defaults to {@code .html}. */
        public Builder suffix(String suffix) {
            this.suffix = suffix;
            return this;
        }

        /**
         * Message bundles for {@code #{...}}, by basename, e.g. {@code com.acme.shop.MessageResources}.
         */
        public Builder messages(String... basenames) {
            messageBasenames.addAll(List.of(basenames));
            return this;
        }

        /**
         * Thymeleaf dialects the templates use, besides the Spring standard dialect (e.g. a dialect
         * the in-house tag library was migrated to).
         */
        public Builder dialect(IDialect dialect) {
            dialects.add(dialect);
            return this;
        }

        /**
         * Maps a fixture's page to its template name (for fixtures without {@code "template"}). By
         * default: the page's path without its {@code .jsp}/{@code .jspf} extension and without a
         * leading {@code /WEB-INF/views/}, {@code /WEB-INF/jsp/}, {@code /WEB-INF/pages/}, or
         * {@code /WEB-INF/} - {@code /WEB-INF/views/orders/list.jsp} becomes {@code orders/list}.
         */
        public Builder templateName(Function<String, String> templateName) {
            this.templateName = templateName;
            return this;
        }

        /**
         * How fixtures compare the output with the expected output; defaults to
         * {@link Comparison#HTML}, whatever the fixture says.
         */
        public Builder comparison(Comparison comparison) {
            this.comparison = comparison;
            return this;
        }

        public ThymeleafTester build() {
            return new ThymeleafTester(this);
        }
    }

    static String defaultTemplateName(String page) {
        String name = page.startsWith("/") ? page.substring(1) : page;
        for (String prefix : List.of("WEB-INF/views/", "WEB-INF/jsp/", "WEB-INF/pages/", "WEB-INF/")) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
                break;
            }
        }
        return name.replaceAll("\\.jspf?$", "");
    }

    /**
     * The template a request renders: its {@link RenderRequest#template(String) template}, or the
     * one its page maps to.
     */
    public String templateFor(RenderRequest request) {
        return request.getTemplate() != null ? request.getTemplate() : templateName.apply(request.getPage());
    }

    @Override
    public Rendered render(RenderRequest request) {
        MockHttpServletRequestBuilder http = MockMvcRequestBuilders
                .request(HttpMethod.valueOf(request.getMethod()), ThymeleafRenderController.PATH)
                .requestAttr(ThymeleafRenderController.TEMPLATE, templateFor(request))
                .locale(request.getLocale());
        request.getParameters().forEach((name, values) -> http.param(name, values.toArray(String[]::new)));
        // A null attribute is an absent one (as in a servlet container); MockMvc rejects nulls.
        request.getRequestAttributes().forEach((name, value) -> {
            if (value != null) {
                http.requestAttr(name, value);
            }
        });
        request.getSessionAttributes().forEach((name, value) -> {
            if (value != null) {
                http.sessionAttr(name, value);
            }
        });
        http.with(servletRequest -> {
            request.getApplicationAttributes().forEach((name, value) -> {
                if (value != null) {
                    servletRequest.getServletContext().setAttribute(name, value);
                }
            });
            return servletRequest;
        });
        try {
            MockHttpServletResponse response = mvc.perform(http).andReturn().getResponse();
            return new Rendered(response.getStatus(), response.getContentType(),
                    response.getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            StringWriter trace = new StringWriter();
            e.printStackTrace(new PrintWriter(trace));
            return new Rendered(500, "text/plain", trace.toString());
        } finally {
            // The servlet context outlives the request: don't leak attributes into the next render.
            request.getApplicationAttributes().keySet()
                    .forEach(mvc.getDispatcherServlet().getServletContext()::removeAttribute);
        }
    }

    /**
     * Renders the request and fails unless the template rendered without error.
     */
    public String renderOk(RenderRequest request) {
        Rendered rendered = render(request);
        if (!rendered.ok()) {
            throw new AssertionError("Rendering template " + templateFor(request) + " (for " + request.getPage() +
                                     ") failed with status " + rendered.status() + ":\n" + rendered.body());
        }
        return rendered.body();
    }

    /**
     * Renders a fixture's template and compares it with the fixture's expected output (which only
     * {@link JspTester} writes).
     */
    public void verify(Path fixture) {
        Fixtures.verify(this, fixture, false);
    }

    /**
     * A dynamic test per fixture ({@code *.json}) in a directory, for a JUnit {@code @TestFactory}.
     */
    public Stream<DynamicTest> fixtureTests(Path directory) {
        return Fixtures.dynamicTests(this, directory, false);
    }

    @Override
    public boolean writesExpectedOutput() {
        return false;
    }

    @Override
    public Comparison comparison(Comparison fixtureComparison) {
        return comparison;
    }
}
