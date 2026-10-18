package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.tree.Jsp;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Whether a page's template text is HTML or XML (e.g. XHTML served as
 * {@code application/xhtml+xml}, or an XML feed). The two have different markup rules: HTML has
 * void elements ({@code <br>}), optional end tags ({@code <p>}, {@code <li>}), raw-text elements
 * ({@code <script>}) and case-insensitive names. XML has none of these. Every element must be
 * closed or self-closed ({@code <br/>}), names are case-sensitive, and CDATA sections hold raw text.
 */
public enum MarkupMode {
    HTML, XML;

    /**
     * @return the mode the page's {@code <%@ page contentType="..." %>} declares (also looking in
     * statically included files, whose page directives apply to the page), or {@code null} if it
     * declares no content type. Without one, JSP's default is {@code text/html}.
     */
    public static @Nullable MarkupMode declared(Jsp.Document document) {
        return declared(document.getNodes());
    }

    /**
     * @return the mode for a MIME type: {@code text/xml}, {@code application/xml} and any
     * {@code +xml} type (such as {@code application/xhtml+xml}) are XML, anything else HTML.
     */
    public static MarkupMode ofContentType(String contentType) {
        int semicolon = contentType.indexOf(';');
        String mimeType = (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
                .trim().toLowerCase(Locale.ROOT);
        return "text/xml".equals(mimeType) || "application/xml".equals(mimeType) || mimeType.endsWith("+xml") ?
                XML : HTML;
    }

    private static @Nullable MarkupMode declared(List<? extends Jsp.Content> nodes) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Directive directive) {
                if ("page".equals(directive.getName())) {
                    for (Jsp.Attribute attribute : directive.getAttributes()) {
                        if ("contentType".equals(attribute.getName())) {
                            return ofContentType(attribute.getValue().getValue());
                        }
                    }
                }
                if (directive.getIncludedFile() != null) {
                    MarkupMode included = declared(directive.getIncludedFile().getNodes());
                    if (included != null) {
                        return included;
                    }
                }
            }
        }
        return null;
    }
}
