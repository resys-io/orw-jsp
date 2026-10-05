package io.resys.orw.jsp;

import lombok.Getter;

import java.nio.file.Path;

/**
 * Thrown when the {@link JspParser} encounters source text it cannot make sense of, e.g. an
 * unterminated {@code <%-- ... --%>}, {@code <% ... %>}, {@code ${...}}, or tag. The message starts
 * with {@code path:line:column:}, pointing at the start of the construct that failed to parse.
 */
@Getter
public class JspParsingException extends RuntimeException {
    private final Path sourcePath;
    /** 1-based. */
    private final int line;
    /** 1-based. */
    private final int column;

    public JspParsingException(Path sourcePath, int line, int column, String message) {
        super(sourcePath + ":" + line + ":" + column + ": " + message);
        this.sourcePath = sourcePath;
        this.line = line;
        this.column = column;
    }
}
