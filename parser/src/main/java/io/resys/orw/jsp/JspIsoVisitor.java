package io.resys.orw.jsp;

import io.resys.orw.jsp.tree.Jsp;

public class JspIsoVisitor<P> extends JspVisitor<P> {

    @Override
    public Jsp.Document visitDocument(Jsp.Document document, P p) {
        return (Jsp.Document) super.visitDocument(document, p);
    }

    @Override
    public Jsp.Text visitText(Jsp.Text text, P p) {
        return (Jsp.Text) super.visitText(text, p);
    }

    @Override
    public Jsp.Comment visitComment(Jsp.Comment comment, P p) {
        return (Jsp.Comment) super.visitComment(comment, p);
    }

    @Override
    public Jsp.Directive visitDirective(Jsp.Directive directive, P p) {
        return (Jsp.Directive) super.visitDirective(directive, p);
    }

    @Override
    public Jsp.IncludedFile visitIncludedFile(Jsp.IncludedFile includedFile, P p) {
        return (Jsp.IncludedFile) super.visitIncludedFile(includedFile, p);
    }

    @Override
    public Jsp.Declaration visitDeclaration(Jsp.Declaration declaration, P p) {
        return (Jsp.Declaration) super.visitDeclaration(declaration, p);
    }

    @Override
    public Jsp.Scriptlet visitScriptlet(Jsp.Scriptlet scriptlet, P p) {
        return (Jsp.Scriptlet) super.visitScriptlet(scriptlet, p);
    }

    @Override
    public Jsp.ExpressionScriptlet visitExpressionScriptlet(Jsp.ExpressionScriptlet expressionScriptlet, P p) {
        return (Jsp.ExpressionScriptlet) super.visitExpressionScriptlet(expressionScriptlet, p);
    }

    @Override
    public Jsp.ExpressionLanguage visitExpressionLanguage(Jsp.ExpressionLanguage expressionLanguage, P p) {
        return (Jsp.ExpressionLanguage) super.visitExpressionLanguage(expressionLanguage, p);
    }

    @Override
    public Jsp.Tag visitTag(Jsp.Tag tag, P p) {
        return (Jsp.Tag) super.visitTag(tag, p);
    }

    @Override
    public Jsp.Tag.Closing visitTagClosing(Jsp.Tag.Closing closing, P p) {
        return (Jsp.Tag.Closing) super.visitTagClosing(closing, p);
    }

    @Override
    public Jsp.Attribute visitAttribute(Jsp.Attribute attribute, P p) {
        return (Jsp.Attribute) super.visitAttribute(attribute, p);
    }

    @Override
    public Jsp.Attribute.Value visitAttributeValue(Jsp.Attribute.Value value, P p) {
        return (Jsp.Attribute.Value) super.visitAttributeValue(value, p);
    }
}
