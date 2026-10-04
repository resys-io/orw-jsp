package io.resys.orw.jsp.tester;

import jakarta.servlet.jsp.PageContext;

import java.util.Map;

/**
 * One use of a mocked tag, as a {@link MockBehavior#custom custom} mock renders it.
 *
 * @param name        the tag, e.g. {@code acme:menu}, with the prefix its library is first declared with
 *                    in the web application (pages normally all use the same one).
 * @param uri         its tag library's uri.
 * @param attributes  its attributes, evaluated (EL and {@code <%= %>} already applied).
 * @param body        its body, rendered (empty if it has none).
 * @param pageContext the page's context, e.g. to read or set page attributes.
 */
public record MockInvocation(String name, String uri, Map<String, Object> attributes, String body,
                             PageContext pageContext) {

    /**
     * @return the attribute's value as text, or {@code null} if the tag doesn't have it.
     */
    public String attribute(String name) {
        Object value = attributes.get(name);
        return value == null ? null : String.valueOf(value);
    }
}
