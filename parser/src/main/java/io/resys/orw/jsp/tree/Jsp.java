package io.resys.orw.jsp.tree;

import lombok.EqualsAndHashCode;
import lombok.With;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import io.resys.orw.jsp.JspVisitor;
import io.resys.orw.jsp.internal.JspPrinter;
import org.openrewrite.marker.Markers;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Lossless Semantic Tree (LST) for the JSP syntax defined by the
 * <a href="https://jakarta.ee/specifications/pages/4.0/jakarta-server-pages-spec-4.0">Jakarta Server Pages 4.0</a>
 * specification (JSP "standard syntax", not the alternative XML/JSP-document syntax).
 * <p>
 * Only constructs the JSP translator itself gives meaning to are modeled structurally:
 * directives, scriptlets/declarations/expressions, JSP comments, Expression Language (EL)
 * expressions, and standard/custom actions (any element whose name contains a namespace
 * prefix, e.g. {@code jsp:useBean} or {@code c:if}). Everything else - including all
 * plain HTML/XML markup - is preserved verbatim as {@link Text}, since the JSP spec does
 * not itself parse template markup; that is left to a downstream HTML/XML pass if one is
 * ever layered on top of this model.
 */
public interface Jsp extends Tree {

    @SuppressWarnings("unchecked")
    @Override
    default <R extends Tree, P> R accept(TreeVisitor<R, P> v, P p) {
        //noinspection DataFlowIssue
        return (R) acceptJsp(v.adapt(JspVisitor.class), p);
    }

    @Override
    default <P> boolean isAcceptable(TreeVisitor<?, P> v, P p) {
        return v.isAdaptableTo(JspVisitor.class);
    }

    default <P> @Nullable Jsp acceptJsp(JspVisitor<P> v, P p) {
        return v.defaultValue(this, p);
    }

    String getPrefix();

    Jsp withPrefix(String prefix);

    /**
     * A node that may appear as a direct child of a {@link Document} or of a {@link Tag}'s body.
     * ({@link Attribute} is deliberately excluded - it may only appear in an attribute list.)
     */
    interface Content extends Jsp {
    }

    /**
     * The root of the tree; one per parsed {@code .jsp}/{@code .jspf} file.
     */
    @lombok.Value
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @With
    class Document implements Jsp, SourceFile {
        @EqualsAndHashCode.Include
        UUID id;

        Markers markers;
        Path sourcePath;
        List<Content> nodes;

        @Nullable // for backwards compatibility, mirrors org.openrewrite.properties.tree.Properties.File
        @With(lombok.AccessLevel.PRIVATE)
        String charsetName;

        boolean charsetBomMarked;

        @Nullable
        FileAttributes fileAttributes;

        @Nullable
        Checksum checksum;

        @Override
        public String getPrefix() {
            return "";
        }

        @Override
        public Document withPrefix(String prefix) {
            return this;
        }

        @Override
        public Charset getCharset() {
            return charsetName == null ? StandardCharsets.UTF_8 : Charset.forName(charsetName);
        }

        @Override
        public SourceFile withCharset(Charset charset) {
            return withCharsetName(charset.name());
        }

        @Override
        public <P> Jsp acceptJsp(JspVisitor<P> v, P p) {
            return v.visitDocument(this, p);
        }

        @Override
        public <P> TreeVisitor<?, PrintOutputCapture<P>> printer(Cursor cursor) {
            return new JspPrinter<>();
        }
    }

    /**
     * Literal template text: HTML/XML markup or any other content the JSP translator does not
     * itself give structure to. Captures everything between recognized JSP constructs verbatim,
     * including any escape sequences (e.g. {@code <\%}, {@code \$}, {@code \#}) exactly as written.
     */
    @lombok.Value
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @With
    class Text implements Content {
        @EqualsAndHashCode.Include
        UUID id;

        Markers markers;
        String text;

        @Override
        public String getPrefix() {
            return "";
        }

        @Override
        public Text withPrefix(String prefix) {
            return prefix.isEmpty() ? this : withText(prefix + text);
        }

        @Override
        public <P> Jsp acceptJsp(JspVisitor<P> v, P p) {
            return v.visitText(this, p);
        }
    }

    /**
     * A JSP comment: {@code <%-- ... --%>}. Unlike an HTML comment, this is stripped by the
     * JSP translator and never sent to the client.
     */
    @lombok.Value
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @With
    class Comment implements Content {
        @EqualsAndHashCode.Include
        UUID id;

        String prefix;
        Markers markers;

        /**
         * The raw text between {@code <%--} and {@code --%>}.
         */
        String text;

        @Override
        public <P> Jsp acceptJsp(JspVisitor<P> v, P p) {
            return v.visitComment(this, p);
        }
    }

    /**
     * A directive: {@code <%@ page ... %>}, {@code <%@ include ... %>}, {@code <%@ taglib ... %>},
     * or (rarely) a custom directive name.
     */
    @lombok.Value
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @With
    class Directive implements Content {
        @EqualsAndHashCode.Include
        UUID id;

        String prefix;
        Markers markers;

        /**
         * Whitespace between {@code <%@} and the directive name.
         */
        String beforeName;

        /**
         * "page", "include", "taglib", or a rarely-used custom directive name.
         */
        String name;

        List<Attribute> attributes;

        /**
         * Whitespace before the closing {@code %>}.
         */
        String beforeDirectiveEnd;

        /**
         * For an {@code <%@ include file="..." %>} directive whose target could be resolved, the
         * parsed content of the included file; {@code null} for every other directive, and for an
         * include whose target could not be found, read, or parsed (in which case the directive
         * carries an {@link org.openrewrite.ParseWarning} marker saying why) or would include itself
         * recursively (no marker - its content is already part of the tree).
         * <p>
         * This is a read-only view: it is never printed (the directive prints as itself, not as the
         * included text), and {@link JspVisitor} visits it but discards any changes made to it, so a
         * recipe can never modify the included file through the page that includes it.
         */
        @Nullable
        IncludedFile includedFile;

        @Override
        public <P> Jsp acceptJsp(JspVisitor<P> v, P p) {
            return v.visitDirective(this, p);
        }
    }

    /**
     * The content of a file statically included by an {@code <%@ include file="..." %>}
     * {@link Directive}, embedded into the including page's tree so that recipes can take it into
     * account (e.g. a taglib declared on the page but only used inside the included fragment).
     * See {@link Directive#getIncludedFile()} for why it is read-only.
     */
    @lombok.Value
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @With
    class IncludedFile implements Jsp {
        @EqualsAndHashCode.Include
        UUID id;

        Markers markers;

        /**
         * The resolved path of the included file, in the same form as {@link Document#getSourcePath()}.
         */
        Path sourcePath;

        List<Content> nodes;

        @Override
        public String getPrefix() {
            return "";
        }

        @Override
        public IncludedFile withPrefix(String prefix) {
            return this;
        }

        @Override
        public <P> Jsp acceptJsp(JspVisitor<P> v, P p) {
            return v.visitIncludedFile(this, p);
        }
    }

    /**
     * A declaration: {@code <%! ... %>}. Declares members (fields/methods) on the generated servlet class.
     */
    @lombok.Value
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @With
    class Declaration implements Content {
        @EqualsAndHashCode.Include
        UUID id;

        String prefix;
        Markers markers;

        /**
         * The raw Java code between {@code <%!} and {@code %>}, including any {@code %\>} escape
         * sequences verbatim.
         *
         * @see #getCode()
         */
        String code;

        /**
         * @return the Java code with {@code %\>} escape sequences resolved to a literal {@code %>}.
         */
        public String getCode() {
            return Escaping.unescapePercentGt(code);
        }

        /**
         * @return the Java code exactly as written, including any {@code %\>} escape sequences.
         */
        public String getCodeSource() {
            return code;
        }

        @Override
        public <P> Jsp acceptJsp(JspVisitor<P> v, P p) {
            return v.visitDeclaration(this, p);
        }
    }

    /**
     * A scriptlet: {@code <% ... %>}. Java code copied verbatim into the generated servlet's service method.
     */
    @lombok.Value
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @With
    class Scriptlet implements Content {
        @EqualsAndHashCode.Include
        UUID id;

        String prefix;
        Markers markers;

        /**
         * @see Declaration#getCode()
         */
        String code;

        public String getCode() {
            return Escaping.unescapePercentGt(code);
        }

        public String getCodeSource() {
            return code;
        }

        @Override
        public <P> Jsp acceptJsp(JspVisitor<P> v, P p) {
            return v.visitScriptlet(this, p);
        }
    }

    /**
     * An expression scriptlet: {@code <%= ... %>}. A Java expression whose value is written to the response.
     */
    @lombok.Value
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @With
    class ExpressionScriptlet implements Content {
        @EqualsAndHashCode.Include
        UUID id;

        String prefix;
        Markers markers;

        /**
         * @see Declaration#getCode()
         */
        String code;

        public String getCode() {
            return Escaping.unescapePercentGt(code);
        }

        public String getCodeSource() {
            return code;
        }

        @Override
        public <P> Jsp acceptJsp(JspVisitor<P> v, P p) {
            return v.visitExpressionScriptlet(this, p);
        }
    }

    /**
     * An Expression Language expression: {@code ${...}} (immediate evaluation) or {@code #{...}}
     * (deferred evaluation, JSP 2.1+). The expression body is kept as raw text; it is not further
     * decomposed into an EL AST.
     */
    @lombok.Value
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @With
    class ExpressionLanguage implements Content {
        @EqualsAndHashCode.Include
        UUID id;

        String prefix;
        Markers markers;
        Type type;

        /**
         * The raw text between the opening {@code {} and its matching closing {@code }}, exactly
         * as written (including any nested braces or string literals).
         */
        String expression;

        @Override
        public <P> Jsp acceptJsp(JspVisitor<P> v, P p) {
            return v.visitExpressionLanguage(this, p);
        }

        public enum Type {
            /** {@code ${...}} */
            IMMEDIATE,
            /** {@code #{...}} */
            DEFERRED
        }
    }

    /**
     * A standard action (e.g. {@code jsp:useBean}) or custom action / custom tag (e.g. {@code c:if}).
     * The JSP spec requires such elements to use XML syntax, so - unlike arbitrary HTML - both the
     * start and (when not self-closing) end tag are always present and are modeled structurally.
     */
    @lombok.Value
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @With
    class Tag implements Content {
        @EqualsAndHashCode.Include
        UUID id;

        String prefix;
        Markers markers;

        /**
         * The fully qualified tag name, including its namespace prefix, e.g. {@code "jsp:useBean"} or {@code "c:if"}.
         */
        String name;

        List<Attribute> attributes;

        boolean selfClosing;

        /**
         * Whitespace before the closing {@code >} or {@code />}.
         */
        String beforeTagDelimiterPrefix;

        /**
         * The tag's body content, or {@code null} if {@link #isSelfClosing()}.
         */
        List<Content> body;

        /**
         * The closing tag, or {@code null} if {@link #isSelfClosing()} or if the end tag is missing
         * (a malformed page; the parser recovers by ending the body at the end of input or at an
         * enclosing tag's end tag, whichever comes first).
         */
        @Nullable
        Closing closing;

        @Override
        public <P> Jsp acceptJsp(JspVisitor<P> v, P p) {
            return v.visitTag(this, p);
        }

        /**
         * The {@code </prefix:name>} that closes a non-self-closing {@link Tag}.
         */
        @lombok.Value
        @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
        @With
        public static class Closing implements Jsp {
            @EqualsAndHashCode.Include
            UUID id;

            /**
             * Whitespace between {@code </} and the name (empty in well-formed documents).
             */
            String prefix;

            Markers markers;
            String name;

            /**
             * Whitespace before the closing {@code >}. In a malformed end tag carrying attributes
             * (e.g. {@code </c:if test="x">}), which the parser tolerates, this holds that whole
             * raw text verbatim instead.
             */
            String beforeTagDelimiterPrefix;

            @Override
            public <P> Jsp acceptJsp(JspVisitor<P> v, P p) {
                return v.visitTagClosing(this, p);
            }
        }
    }

    /**
     * An {@code name="value"} pair on a {@link Directive} or {@link Tag}. The JSP spec requires
     * attribute values to be quoted (unlike bare HTML attributes).
     */
    @lombok.Value
    @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
    @With
    class Attribute implements Jsp {
        @EqualsAndHashCode.Include
        UUID id;

        String prefix;
        Markers markers;
        String name;

        /**
         * Whitespace before the {@code =}.
         */
        String beforeEquals;

        Value value;

        @Override
        public <P> Jsp acceptJsp(JspVisitor<P> v, P p) {
            return v.visitAttribute(this, p);
        }

        /**
         * The quoted value of an {@link Attribute}. The value text is kept raw (not unescaped) and
         * may itself contain EL expressions or JSP quoting escapes as plain text.
         */
        @lombok.Value
        @EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
        @With
        public static class Value implements Jsp {
            @EqualsAndHashCode.Include
            UUID id;

            /**
             * Whitespace between {@code =} and the opening quote.
             */
            String prefix;

            Markers markers;

            /**
             * The quote character used to delimit the value: {@code '"'} or {@code '\''}.
             */
            char quote;

            /**
             * The raw text between the quotes, exactly as written.
             */
            String value;

            @Override
            public <P> Jsp acceptJsp(JspVisitor<P> v, P p) {
                return v.visitAttributeValue(this, p);
            }
        }
    }

    class Escaping {
        private static final Pattern PERCENT_GT_ESCAPE = Pattern.compile("%\\\\>");

        private Escaping() {
        }

        static String unescapePercentGt(String s) {
            return PERCENT_GT_ESCAPE.matcher(s).replaceAll("%>");
        }
    }
}
