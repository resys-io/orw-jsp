package io.resys.orw.jsp.tester;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What to render and with which inputs: the page (or, for other renderers, template), request
 * parameters, request/session/application attributes, the locale, and mock behaviors for mocked
 * tags. Engine-neutral, so the same inputs can drive a JSP and its migrated template.
 */
public final class RenderRequest {

    private final String page;
    private String method = "GET";
    private final Map<String, List<String>> parameters = new LinkedHashMap<>();
    private final Map<String, Object> requestAttributes = new LinkedHashMap<>();
    private final Map<String, Object> sessionAttributes = new LinkedHashMap<>();
    private final Map<String, Object> applicationAttributes = new LinkedHashMap<>();
    private Locale locale = Locale.ENGLISH;
    private String template;
    private final Map<String, MockBehavior> mocks = new LinkedHashMap<>();

    private RenderRequest(String page) {
        this.page = page.startsWith("/") ? page : "/" + page;
    }

    /**
     * @param page the page's path in the web application, e.g. {@code /WEB-INF/views/orders.jsp}.
     */
    public static RenderRequest page(String page) {
        return new RenderRequest(page);
    }

    public RenderRequest method(String method) {
        this.method = method.toUpperCase(Locale.ROOT);
        return this;
    }

    public RenderRequest param(String name, String... values) {
        parameters.computeIfAbsent(name, n -> new ArrayList<>()).addAll(List.of(values));
        return this;
    }

    public RenderRequest requestAttribute(String name, Object value) {
        requestAttributes.put(name, value);
        return this;
    }

    public RenderRequest sessionAttribute(String name, Object value) {
        sessionAttributes.put(name, value);
        return this;
    }

    public RenderRequest applicationAttribute(String name, Object value) {
        applicationAttributes.put(name, value);
        return this;
    }

    /**
     * The template a page was migrated to (e.g. {@code orders} for Thymeleaf), when a renderer's
     * own mapping from the page path doesn't give it.
     */
    public RenderRequest template(String template) {
        this.template = template;
        return this;
    }

    public RenderRequest locale(Locale locale) {
        this.locale = locale;
        return this;
    }

    /**
     * @param tag a mocked tag as written on the page, e.g. {@code acme:widget}.
     */
    public RenderRequest mock(String tag, MockBehavior behavior) {
        mocks.put(tag, behavior);
        return this;
    }

    public String getPage() {
        return page;
    }

    public String getMethod() {
        return method;
    }

    public Map<String, List<String>> getParameters() {
        return Collections.unmodifiableMap(parameters);
    }

    public Map<String, Object> getRequestAttributes() {
        return Collections.unmodifiableMap(requestAttributes);
    }

    public Map<String, Object> getSessionAttributes() {
        return Collections.unmodifiableMap(sessionAttributes);
    }

    public Map<String, Object> getApplicationAttributes() {
        return Collections.unmodifiableMap(applicationAttributes);
    }

    /**
     * @return the template set with {@link #template(String)}, or {@code null}.
     */
    public String getTemplate() {
        return template;
    }

    public Locale getLocale() {
        return locale;
    }

    public Map<String, MockBehavior> getMocks() {
        return Collections.unmodifiableMap(mocks);
    }
}
