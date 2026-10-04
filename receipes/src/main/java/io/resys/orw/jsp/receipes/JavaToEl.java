package io.resys.orw.jsp.receipes;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Translates a Java expression from a scriptlet into an equivalent EL expression body, for the
 * safe subset where that is possible, for {@link ScriptletToJstlConverter}. A small
 * recursive-descent parser; anything outside the subset makes the translation fail, with a reason.
 * <p>
 * Supported:
 * <ul>
 *     <li>literals ({@code "s"} becomes {@code 's'}), {@code true}/{@code false}/{@code null};</li>
 *     <li>names EL can see (page attributes the caller says exist);</li>
 *     <li>{@code a.getB()} (as {@code a.b}), {@code a[i]}, casts (dropped);</li>
 *     <li>{@code request.getParameter("p")} ({@code param.p}),
 *     {@code request/session/application/pageContext.getAttribute("x")} ({@code requestScope.x}...),
 *     {@code request.getSession().getAttribute("x")};</li>
 *     <li>{@code a.equals(b)} ({@code a == b}), {@code a.isEmpty()} ({@code empty a}),
 *     {@code a.size()}/{@code a.length()} ({@code fn:length(a)}, which needs the JSTL functions
 *     library), {@code a.toString()} (dropped);</li>
 *     <li>{@code == != < > <= >= && || !}, {@code - * %}, {@code ?:}, parentheses.</li>
 * </ul>
 * Not supported, because EL would behave differently: {@code +} (EL can't concatenate strings) and
 * {@code /} (EL divides in floating point); {@code isX()} getters (EL only recognizes them for
 * primitive {@code boolean}); other method calls, {@code new}, {@code instanceof}, assignments.
 * Note that {@code ==} on objects compares with {@code equals()} in EL, not by reference.
 */
final class JavaToEl {

    /**
     * @param el            the EL expression body (without <code>${</code>/<code>}</code>), or {@code null}.
     * @param usesFunctions whether it uses {@code fn:} functions.
     * @param failure       why it couldn't be translated, if it couldn't.
     */
    record Result(@Nullable String el, boolean usesFunctions, @Nullable String failure) {
        boolean ok() {
            return el != null;
        }
    }

    private static final class Fail extends RuntimeException {
        Fail(String message) {
            super(message, null, false, false);
        }
    }

    private enum Kind { NAME, NUMBER, STRING, CHAR, OP, END }

    private record Token(Kind kind, String text) {
    }

    /** A translated sub-expression: its EL text and binding strength (higher binds tighter). */
    private record Expr(String el, int precedence, boolean isStringLiteral) {
        static Expr atom(String el) {
            return new Expr(el, 100, false);
        }
    }

    /** A primary that's only valid as the receiver of a call: request, session, ... */
    private record Implicit(String name) {
    }

    private static final Set<String> IMPLICIT = Set.of("request", "session", "application", "pageContext");

    private final List<Token> tokens;
    private final Set<String> elVisible;
    private int pos;
    private boolean usesFunctions;

    private JavaToEl(List<Token> tokens, Set<String> elVisible) {
        this.tokens = tokens;
        this.elVisible = elVisible;
    }

    /**
     * @param elVisible the Java names that are also page attributes with the same value, so EL
     *                  can refer to them by name.
     */
    static Result translate(String java, Set<String> elVisible) {
        try {
            JavaToEl parser = new JavaToEl(tokenize(java), elVisible);
            Expr expr = parser.ternary();
            if (parser.peek().kind() != Kind.END) {
                throw new Fail("unexpected '" + parser.peek().text() + "'");
            }
            return new Result(expr.el(), parser.usesFunctions, null);
        } catch (Fail e) {
            return new Result(null, false, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------------------------
    // Parsing, lowest precedence first
    // -----------------------------------------------------------------------------------------

    private Expr ternary() {
        Expr condition = or();
        if (accept("?")) {
            Expr whenTrue = ternary();
            expect(":");
            Expr whenFalse = ternary();
            return new Expr(wrap(condition, 2) + " ? " + wrap(whenTrue, 2) + " : " + wrap(whenFalse, 2), 1, false);
        }
        return condition;
    }

    private Expr or() {
        Expr left = and();
        while (accept("||")) {
            left = binary(left, "or", and(), 2);
        }
        return left;
    }

    private Expr and() {
        Expr left = equality();
        while (accept("&&")) {
            left = binary(left, "and", equality(), 3);
        }
        return left;
    }

    private Expr equality() {
        Expr left = relational();
        while (true) {
            if (accept("==")) {
                left = binary(left, "==", relational(), 4);
            } else if (accept("!=")) {
                left = binary(left, "!=", relational(), 4);
            } else {
                return left;
            }
        }
    }

    private Expr relational() {
        Expr left = additive();
        while (true) {
            String op = peek().kind() == Kind.OP ? peek().text() : "";
            String el = switch (op) {
                case "<" -> "lt";
                case ">" -> "gt";
                case "<=" -> "le";
                case ">=" -> "ge";
                default -> null;
            };
            if (el == null) {
                if (peek().kind() == Kind.NAME && "instanceof".equals(peek().text())) {
                    throw new Fail("instanceof has no EL equivalent");
                }
                return left;
            }
            pos++;
            left = binary(left, el, additive(), 5);
        }
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (true) {
            if (accept("+")) {
                throw new Fail("'+' can't be translated: EL's + doesn't concatenate strings");
            } else if (accept("-")) {
                left = binary(left, "-", multiplicative(), 6);
            } else {
                return left;
            }
        }
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (true) {
            if (accept("*")) {
                left = binary(left, "*", unary(), 7);
            } else if (accept("%")) {
                left = binary(left, "%", unary(), 7);
            } else if (accept("/")) {
                throw new Fail("'/' can't be translated: EL divides in floating point");
            } else {
                return left;
            }
        }
    }

    private Expr unary() {
        if (accept("!")) {
            return new Expr("not " + wrap(unary(), 8), 8, false);
        }
        if (accept("-")) {
            return new Expr("-" + wrap(unary(), 8), 8, false);
        }
        if (peek().kind() == Kind.OP && ("++".equals(peek().text()) || "--".equals(peek().text()))) {
            throw new Fail("'" + peek().text() + "' changes a variable");
        }
        // A cast: (Type) operand, (Type[]) operand, (pkg.Type<X>) operand.
        int save = pos;
        if (accept("(") && skipType() && accept(")") && startsOperand()) {
            return unary();
        }
        pos = save;
        return postfix();
    }

    private Expr postfix() {
        Object receiver = primary();
        while (true) {
            if (accept(".")) {
                Token member = next();
                if (member.kind() != Kind.NAME) {
                    throw new Fail("unexpected '" + member.text() + "'");
                }
                if (!accept("(")) {
                    throw new Fail("field access '." + member.text() + "' can't be translated");
                }
                List<Expr> arguments = arguments();
                receiver = call(receiver, member.text(), arguments);
            } else if (accept("[")) {
                Expr index = ternary();
                expect("]");
                receiver = Expr.atom(value(receiver).el() + "[" + index.el() + "]");
            } else if (peek().kind() == Kind.OP && ("++".equals(peek().text()) || "--".equals(peek().text()))) {
                throw new Fail("'" + peek().text() + "' changes a variable");
            } else {
                return value(receiver);
            }
        }
    }

    private Object primary() {
        Token token = next();
        switch (token.kind()) {
            case STRING -> {
                return new Expr(elString(token.text()), 100, true);
            }
            case CHAR -> {
                return new Expr(elString(token.text()), 100, true);
            }
            case NUMBER -> {
                return Expr.atom(token.text().replaceAll("[lLfFdD]$", ""));
            }
            case NAME -> {
                String name = token.text();
                if (Set.of("true", "false", "null").contains(name)) {
                    return Expr.atom(name);
                }
                if ("new".equals(name)) {
                    throw new Fail("object creation can't be translated");
                }
                if (IMPLICIT.contains(name)) {
                    return new Implicit(name);
                }
                if (!elVisible.contains(name)) {
                    if (Character.isUpperCase(name.charAt(0))) {
                        throw new Fail("'" + name + "' is a class: static members can't be translated");
                    }
                    throw new Fail("'" + name + "' is a Java variable EL can't see (not mirrored into a page " +
                                   "attribute)");
                }
                return Expr.atom(name);
            }
            case OP -> {
                if ("(".equals(token.text())) {
                    Expr inner = ternary();
                    expect(")");
                    // Parentheses around a single value (e.g. left over from a cast) are redundant.
                    return inner.precedence() == 100 ? inner :
                            new Expr("(" + inner.el() + ")", 100, inner.isStringLiteral());
                }
                throw new Fail("unexpected '" + token.text() + "'");
            }
            default -> throw new Fail("incomplete expression");
        }
    }

    private Object call(Object receiver, String method, List<Expr> arguments) {
        if (receiver instanceof Implicit) {
            String implicit = ((Implicit) receiver).name();
            if ("request".equals(implicit) && "getSession".equals(method) && arguments.isEmpty()) {
                return new Implicit("session");
            }
            if (arguments.size() == 1 && arguments.get(0).isStringLiteral()) {
                String key = arguments.get(0).el();
                String scope = switch (method) {
                    case "getParameter" -> "request".equals(implicit) ? "param" : null;
                    case "getAttribute" -> switch (implicit) {
                        case "request" -> "requestScope";
                        case "session" -> "sessionScope";
                        case "application" -> "applicationScope";
                        default -> "pageScope";
                    };
                    default -> null;
                };
                if (scope != null) {
                    String plain = key.substring(1, key.length() - 1);
                    return Expr.atom(plain.matches("[A-Za-z_$][\\w$]*") ? scope + "." + plain : scope + "[" + key + "]");
                }
            }
            throw new Fail("'" + implicit + "." + method + "(...)' can't be translated");
        }
        Expr target = (Expr) receiver;
        if (method.startsWith("get") && method.length() > 3 && Character.isUpperCase(method.charAt(3)) &&
            arguments.isEmpty()) {
            return Expr.atom(wrap(target, 100) + "." + property(method.substring(3)));
        }
        switch (method) {
            case "equals" -> {
                requireArguments(method, arguments, 1);
                return new Expr(wrap(target, 4) + " == " + wrap(arguments.get(0), 5), 4, false);
            }
            case "isEmpty" -> {
                requireArguments(method, arguments, 0);
                return new Expr("empty " + wrap(target, 8), 8, false);
            }
            case "size", "length" -> {
                requireArguments(method, arguments, 0);
                usesFunctions = true;
                return Expr.atom("fn:length(" + target.el() + ")");
            }
            case "toString" -> {
                requireArguments(method, arguments, 0);
                return target;
            }
            default -> throw new Fail("method call '" + method + "(...)' can't be translated");
        }
    }

    private Expr value(Object receiver) {
        if (receiver instanceof Implicit) {
            throw new Fail("'" + ((Implicit) receiver).name() + "' itself can't be translated");
        }
        return (Expr) receiver;
    }

    private List<Expr> arguments() {
        List<Expr> arguments = new ArrayList<>();
        if (accept(")")) {
            return arguments;
        }
        do {
            arguments.add(ternary());
        } while (accept(","));
        expect(")");
        return arguments;
    }

    private static void requireArguments(String method, List<Expr> arguments, int count) {
        if (arguments.size() != count) {
            throw new Fail("'" + method + "' with " + arguments.size() + " arguments can't be translated");
        }
    }

    /**
     * Skips a type name as in a cast; {@code false} if what follows isn't one.
     */
    private boolean skipType() {
        if (peek().kind() != Kind.NAME || !Character.isUpperCase(peek().text().charAt(0)) &&
                                          !Set.of("int", "long", "double", "float", "short", "byte", "char",
                                                  "boolean").contains(peek().text()) &&
                                          !(pos + 1 < tokens.size() && ".".equals(tokens.get(pos + 1).text()))) {
            return false;
        }
        next();
        while (accept(".")) {
            if (next().kind() != Kind.NAME) {
                return false;
            }
        }
        if (accept("<")) {
            int depth = 1;
            while (depth > 0 && peek().kind() != Kind.END) {
                String t = next().text();
                if ("<".equals(t)) {
                    depth++;
                } else if (">".equals(t)) {
                    depth--;
                }
            }
        }
        while (accept("[")) {
            if (!accept("]")) {
                return false;
            }
        }
        return true;
    }

    private boolean startsOperand() {
        Token t = peek();
        return t.kind() == Kind.NAME || t.kind() == Kind.STRING || t.kind() == Kind.NUMBER ||
               t.kind() == Kind.CHAR || (t.kind() == Kind.OP && ("(".equals(t.text()) || "!".equals(t.text())));
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private static Expr binary(Expr left, String op, Expr right, int precedence) {
        return new Expr(wrap(left, precedence) + " " + op + " " + wrap(right, precedence + 1), precedence, false);
    }

    private static String wrap(Expr expr, int precedence) {
        return expr.precedence() < precedence ? "(" + expr.el() + ")" : expr.el();
    }

    private static String property(String capitalized) {
        return capitalized.length() > 1 && Character.isUpperCase(capitalized.charAt(1)) ? capitalized :
                Character.toLowerCase(capitalized.charAt(0)) + capitalized.substring(1);
    }

    /**
     * A Java string or char literal (with its quotes) as an EL single-quoted string.
     */
    private static String elString(String javaLiteral) {
        String body = javaLiteral.substring(1, javaLiteral.length() - 1);
        StringBuilder out = new StringBuilder("'");
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '\\' && i + 1 < body.length()) {
                char escaped = body.charAt(++i);
                switch (escaped) {
                    case '"' -> out.append('"');
                    case '\'' -> out.append("\\'");
                    case '\\' -> out.append("\\\\");
                    default -> throw new Fail("the escape \\" + escaped + " in a string can't be translated");
                }
            } else if (c == '\'') {
                out.append("\\'");
            } else {
                out.append(c);
            }
        }
        return out.append('\'').toString();
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token next() {
        Token t = tokens.get(pos);
        if (t.kind() != Kind.END) {
            pos++;
        }
        return t;
    }

    private boolean accept(String op) {
        if (peek().kind() == Kind.OP && peek().text().equals(op)) {
            pos++;
            return true;
        }
        return false;
    }

    private void expect(String op) {
        if (!accept(op)) {
            throw new Fail("expected '" + op + "'");
        }
    }

    private static final List<String> OPERATORS = List.of(
            ">>>=", "<<=", ">>=", ">>>", "==", "!=", "<=", ">=", "&&", "||", "++", "--", "+=", "-=", "*=", "/=",
            "%=", "&=", "|=", "^=", "<<", ">>", "->", "::",
            "(", ")", "[", "]", ".", ",", "?", ":", "!", "<", ">", "+", "-", "*", "/", "%", "=", "&", "|", "^", "~",
            "{", "}", ";", "@");

    private static List<Token> tokenize(String java) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int n = java.length();
        while (i < n) {
            char c = java.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < n && (java.charAt(i + 1) == '/' || java.charAt(i + 1) == '*')) {
                throw new Fail("comments in the expression can't be translated");
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = i;
                while (i < n && Character.isJavaIdentifierPart(java.charAt(i))) {
                    i++;
                }
                tokens.add(new Token(Kind.NAME, java.substring(start, i)));
            } else if (Character.isDigit(c)) {
                int start = i;
                while (i < n && (Character.isLetterOrDigit(java.charAt(i)) || java.charAt(i) == '.' ||
                                 java.charAt(i) == '_')) {
                    i++;
                }
                tokens.add(new Token(Kind.NUMBER, java.substring(start, i).replace("_", "")));
            } else if (c == '"' || c == '\'') {
                int start = i++;
                while (i < n && java.charAt(i) != c) {
                    if (java.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                if (i >= n) {
                    throw new Fail("unterminated literal");
                }
                i++;
                tokens.add(new Token(c == '"' ? Kind.STRING : Kind.CHAR, java.substring(start, i)));
            } else {
                String op = null;
                for (String candidate : OPERATORS) {
                    if (java.startsWith(candidate, i)) {
                        op = candidate;
                        break;
                    }
                }
                if (op == null) {
                    throw new Fail("unexpected '" + c + "'");
                }
                if (Set.of("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", ">>>=").contains(op)) {
                    throw new Fail("assignments can't be translated");
                }
                if (Set.of("->", "::", "{", "}", ";", "@", "&", "|", "^", "~", "<<", ">>", ">>>").contains(op)) {
                    throw new Fail("'" + op + "' can't be translated");
                }
                tokens.add(new Token(Kind.OP, op));
                i += op.length();
            }
        }
        tokens.add(new Token(Kind.END, ""));
        return tokens;
    }
}
