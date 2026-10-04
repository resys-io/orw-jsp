package io.resys.orw.jsp.tester;

import jakarta.servlet.jsp.JspException;
import jakarta.servlet.jsp.JspWriter;
import jakarta.servlet.jsp.PageContext;
import jakarta.servlet.jsp.tagext.BodyTagSupport;
import jakarta.servlet.jsp.tagext.DynamicAttributes;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The handler of every mocked tag. The tester generates one trivial subclass per mocked tag (a
 * classic tag handler never learns its own name otherwise), and a TLD mapping each tag to its
 * subclass, accepting any attributes and any body content, scriptlets included.
 * <p>
 * Its output follows the {@link MockBehavior} the {@link RenderRequest} gives for the tag, by
 * default {@link MockBehavior.Mode#PLACEHOLDER}.
 */
public abstract class MockTag extends BodyTagSupport implements DynamicAttributes {

    /** Request attribute holding the render's behaviors, by {@link #key(String, String)}. */
    public static final String BEHAVIORS = MockTag.class.getName() + ".behaviors";

    private final String uri;
    private final String localName;
    private final String prefix;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private MockBehavior behavior = MockBehavior.placeholder();

    protected MockTag(String uri, String localName, String prefix) {
        this.uri = uri;
        this.localName = localName;
        this.prefix = prefix;
    }

    public static String key(String uri, String localName) {
        return uri + "|" + localName;
    }

    @Override
    public void setDynamicAttribute(String namespace, String name, Object value) {
        attributes.put(name, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public int doStartTag() throws JspException {
        Object behaviors = pageContext.getRequest().getAttribute(BEHAVIORS);
        if (behaviors instanceof Map) {
            MockBehavior configured = ((Map<String, MockBehavior>) behaviors).get(key(uri, localName));
            if (configured != null) {
                behavior = configured;
            }
        }
        behavior.variables().forEach((name, value) -> pageContext.setAttribute(name, value, PageContext.PAGE_SCOPE));
        JspWriter out = pageContext.getOut();
        try {
            switch (behavior.mode()) {
                case PLACEHOLDER -> {
                    out.write("<" + prefix + ":" + localName);
                    for (Map.Entry<String, Object> attribute : attributes.entrySet()) {
                        out.write(" " + attribute.getKey() + "=\"" + escape(String.valueOf(attribute.getValue())) + "\"");
                    }
                    out.write(">");
                    return EVAL_BODY_INCLUDE;
                }
                case BODY -> {
                    return EVAL_BODY_INCLUDE;
                }
                case TEXT -> {
                    out.write(behavior.text());
                    return SKIP_BODY;
                }
                case CUSTOM -> {
                    // Render the body into a buffer, for the custom renderer to use at the end tag.
                    return EVAL_BODY_BUFFERED;
                }
                default -> {
                    return SKIP_BODY;
                }
            }
        } catch (IOException e) {
            throw new JspException(e);
        }
    }

    @Override
    public int doEndTag() throws JspException {
        try {
            if (behavior.mode() == MockBehavior.Mode.PLACEHOLDER) {
                pageContext.getOut().write("</" + prefix + ":" + localName + ">");
            } else if (behavior.mode() == MockBehavior.Mode.CUSTOM) {
                String body = bodyContent == null ? "" : bodyContent.getString();
                // Not Map.copyOf: an attribute may evaluate to null.
                MockInvocation invocation = new MockInvocation(prefix + ":" + localName, uri,
                        Collections.unmodifiableMap(new LinkedHashMap<>(attributes)), body, pageContext);
                String output;
                try {
                    output = behavior.renderer().apply(invocation);
                } catch (RuntimeException e) {
                    throw new JspException("The custom mock of <" + prefix + ":" + localName + "> failed", e);
                }
                pageContext.getOut().write(output == null ? "" : output);
            }
        } catch (IOException e) {
            throw new JspException(e);
        } finally {
            attributes.clear();
            behavior = MockBehavior.placeholder();
        }
        return EVAL_PAGE;
    }

    /**
     * What a mocked EL function returns: its call, as text.
     */
    public static String function(String prefix, String name, Object... arguments) {
        StringBuilder call = new StringBuilder(prefix).append(':').append(name).append('(');
        for (int i = 0; i < arguments.length; i++) {
            call.append(i == 0 ? "" : ", ").append(arguments[i]);
        }
        return call.append(')').toString();
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
    }
}
