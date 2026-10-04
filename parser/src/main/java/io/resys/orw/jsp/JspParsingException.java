package io.resys.orw.jsp;

/**
 * Thrown when the {@link JspParser} encounters source text it cannot make sense of, e.g. an
 * unterminated {@code <%-- ... --%>}, {@code <% ... %>}, {@code ${...}}, or tag.
 */
public class JspParsingException extends RuntimeException {
    public JspParsingException(String message) {
        super(message);
    }
}
