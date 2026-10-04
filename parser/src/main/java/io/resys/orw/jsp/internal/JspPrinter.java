package io.resys.orw.jsp.internal;

import org.openrewrite.Cursor;
import org.openrewrite.PrintOutputCapture;
import io.resys.orw.jsp.JspVisitor;
import io.resys.orw.jsp.tree.Jsp;
import org.openrewrite.marker.Marker;
import org.openrewrite.marker.Markers;

import java.util.function.UnaryOperator;

/**
 * Prints a {@link Jsp} LST back to source, reproducing the original text byte-for-byte when the
 * tree is unmodified.
 */
public class JspPrinter<P> extends JspVisitor<PrintOutputCapture<P>> {

    private static final UnaryOperator<String> JSP_MARKER_WRAPPER =
            out -> "~~" + out + (out.isEmpty() ? "" : "~~") + ">";

    @Override
    public Jsp visitDocument(Jsp.Document document, PrintOutputCapture<P> p) {
        beforeSyntax(document, p);
        visit(document.getNodes(), p);
        afterSyntax(document, p);
        return document;
    }

    @Override
    public Jsp visitText(Jsp.Text text, PrintOutputCapture<P> p) {
        beforeSyntax(text, p);
        p.append(text.getText());
        afterSyntax(text, p);
        return text;
    }

    @Override
    public Jsp visitComment(Jsp.Comment comment, PrintOutputCapture<P> p) {
        beforeSyntax(comment, p);
        p.append("<%--").append(comment.getText()).append("--%>");
        afterSyntax(comment, p);
        return comment;
    }

    @Override
    public Jsp visitDirective(Jsp.Directive directive, PrintOutputCapture<P> p) {
        beforeSyntax(directive, p);
        p.append("<%@").append(directive.getBeforeName()).append(directive.getName());
        visit(directive.getAttributes(), p);
        p.append(directive.getBeforeDirectiveEnd()).append("%>");
        afterSyntax(directive, p);
        return directive;
    }

    @Override
    public Jsp visitDeclaration(Jsp.Declaration declaration, PrintOutputCapture<P> p) {
        beforeSyntax(declaration, p);
        p.append("<%!").append(declaration.getCodeSource()).append("%>");
        afterSyntax(declaration, p);
        return declaration;
    }

    @Override
    public Jsp visitScriptlet(Jsp.Scriptlet scriptlet, PrintOutputCapture<P> p) {
        beforeSyntax(scriptlet, p);
        p.append("<%").append(scriptlet.getCodeSource()).append("%>");
        afterSyntax(scriptlet, p);
        return scriptlet;
    }

    @Override
    public Jsp visitExpressionScriptlet(Jsp.ExpressionScriptlet expressionScriptlet, PrintOutputCapture<P> p) {
        beforeSyntax(expressionScriptlet, p);
        p.append("<%=").append(expressionScriptlet.getCodeSource()).append("%>");
        afterSyntax(expressionScriptlet, p);
        return expressionScriptlet;
    }

    @Override
    public Jsp visitExpressionLanguage(Jsp.ExpressionLanguage expressionLanguage, PrintOutputCapture<P> p) {
        beforeSyntax(expressionLanguage, p);
        p.append(expressionLanguage.getType() == Jsp.ExpressionLanguage.Type.DEFERRED ? "#{" : "${");
        p.append(expressionLanguage.getExpression());
        p.append('}');
        afterSyntax(expressionLanguage, p);
        return expressionLanguage;
    }

    @Override
    public Jsp visitTag(Jsp.Tag tag, PrintOutputCapture<P> p) {
        beforeSyntax(tag, p);
        p.append('<').append(tag.getName());
        visit(tag.getAttributes(), p);
        p.append(tag.getBeforeTagDelimiterPrefix());
        if (tag.isSelfClosing()) {
            p.append("/>");
        } else {
            p.append('>');
            visit(tag.getBody(), p);
            Jsp.Tag.Closing closing = tag.getClosing();
            if (closing != null) {
                p.append("</").append(closing.getPrefix()).append(closing.getName())
                        .append(closing.getBeforeTagDelimiterPrefix()).append('>');
            }
        }
        afterSyntax(tag, p);
        return tag;
    }

    @Override
    public Jsp visitAttribute(Jsp.Attribute attribute, PrintOutputCapture<P> p) {
        beforeSyntax(attribute, p);
        p.append(attribute.getName()).append(attribute.getBeforeEquals()).append('=');
        visit(attribute.getValue(), p);
        afterSyntax(attribute, p);
        return attribute;
    }

    @Override
    public Jsp visitAttributeValue(Jsp.Attribute.Value value, PrintOutputCapture<P> p) {
        beforeSyntax(value.getPrefix(), value.getMarkers(), p);
        p.append(value.getQuote()).append(value.getValue()).append(value.getQuote());
        afterSyntax(value.getMarkers(), p);
        return value;
    }

    private void beforeSyntax(Jsp j, PrintOutputCapture<P> p) {
        beforeSyntax(j.getPrefix(), j.getMarkers(), p);
    }

    private void beforeSyntax(String prefix, Markers markers, PrintOutputCapture<P> p) {
        for (Marker marker : markers.getMarkers()) {
            p.append(p.getMarkerPrinter().beforePrefix(marker, new Cursor(getCursor(), marker), JSP_MARKER_WRAPPER));
        }
        p.append(prefix);
        visitMarkers(markers, p);
        for (Marker marker : markers.getMarkers()) {
            p.append(p.getMarkerPrinter().beforeSyntax(marker, new Cursor(getCursor(), marker), JSP_MARKER_WRAPPER));
        }
    }

    private void afterSyntax(Jsp j, PrintOutputCapture<P> p) {
        afterSyntax(j.getMarkers(), p);
    }

    private void afterSyntax(Markers markers, PrintOutputCapture<P> p) {
        for (Marker marker : markers.getMarkers()) {
            p.append(p.getMarkerPrinter().afterSyntax(marker, new Cursor(getCursor(), marker), JSP_MARKER_WRAPPER));
        }
    }
}
