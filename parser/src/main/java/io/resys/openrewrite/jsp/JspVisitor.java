package io.resys.openrewrite.jsp;

import org.openrewrite.SourceFile;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import io.resys.openrewrite.jsp.tree.Jsp;

public class JspVisitor<P> extends TreeVisitor<Jsp, P> {

    @Override
    public boolean isAcceptable(SourceFile sourceFile, P p) {
        return sourceFile instanceof Jsp.Document;
    }

    @Override
    public String getLanguage() {
        return "jsp";
    }

    public Jsp visitDocument(Jsp.Document document, P p) {
        Jsp.Document d = document;
        d = d.withMarkers(visitMarkers(d.getMarkers(), p));
        return d.withNodes(ListUtils.map(d.getNodes(), c -> (Jsp.Content) visit(c, p)));
    }

    public Jsp visitText(Jsp.Text text, P p) {
        Jsp.Text t = text;
        return t.withMarkers(visitMarkers(t.getMarkers(), p));
    }

    public Jsp visitComment(Jsp.Comment comment, P p) {
        Jsp.Comment c = comment;
        return c.withMarkers(visitMarkers(c.getMarkers(), p));
    }

    public Jsp visitDirective(Jsp.Directive directive, P p) {
        Jsp.Directive d = directive;
        d = d.withMarkers(visitMarkers(d.getMarkers(), p));
        d = d.withAttributes(ListUtils.map(d.getAttributes(), a -> (Jsp.Attribute) visit(a, p)));
        if (d.getIncludedFile() != null) {
            // Visited so that visitors can observe the included content, but the result is
            // deliberately discarded: the included file must never be modified through the page
            // that includes it.
            visit(d.getIncludedFile(), p);
        }
        return d;
    }

    public Jsp visitIncludedFile(Jsp.IncludedFile includedFile, P p) {
        Jsp.IncludedFile i = includedFile;
        i = i.withMarkers(visitMarkers(i.getMarkers(), p));
        return i.withNodes(ListUtils.map(i.getNodes(), c -> (Jsp.Content) visit(c, p)));
    }

    public Jsp visitDeclaration(Jsp.Declaration declaration, P p) {
        Jsp.Declaration d = declaration;
        return d.withMarkers(visitMarkers(d.getMarkers(), p));
    }

    public Jsp visitScriptlet(Jsp.Scriptlet scriptlet, P p) {
        Jsp.Scriptlet s = scriptlet;
        return s.withMarkers(visitMarkers(s.getMarkers(), p));
    }

    public Jsp visitExpressionScriptlet(Jsp.ExpressionScriptlet expressionScriptlet, P p) {
        Jsp.ExpressionScriptlet e = expressionScriptlet;
        return e.withMarkers(visitMarkers(e.getMarkers(), p));
    }

    public Jsp visitExpressionLanguage(Jsp.ExpressionLanguage expressionLanguage, P p) {
        Jsp.ExpressionLanguage e = expressionLanguage;
        return e.withMarkers(visitMarkers(e.getMarkers(), p));
    }

    public Jsp visitTag(Jsp.Tag tag, P p) {
        Jsp.Tag t = tag;
        t = t.withMarkers(visitMarkers(t.getMarkers(), p));
        t = t.withAttributes(ListUtils.map(t.getAttributes(), a -> (Jsp.Attribute) visit(a, p)));
        if (t.getBody() != null) {
            t = t.withBody(ListUtils.map(t.getBody(), c -> (Jsp.Content) visit(c, p)));
        }
        return t;
    }

    public Jsp visitTagClosing(Jsp.Tag.Closing closing, P p) {
        Jsp.Tag.Closing c = closing;
        return c.withMarkers(visitMarkers(c.getMarkers(), p));
    }

    public Jsp visitAttribute(Jsp.Attribute attribute, P p) {
        Jsp.Attribute a = attribute;
        a = a.withMarkers(visitMarkers(a.getMarkers(), p));
        return a.withValue((Jsp.Attribute.Value) visit(a.getValue(), p));
    }

    public Jsp visitAttributeValue(Jsp.Attribute.Value value, P p) {
        Jsp.Attribute.Value v = value;
        return v.withMarkers(visitMarkers(v.getMarkers(), p));
    }
}
