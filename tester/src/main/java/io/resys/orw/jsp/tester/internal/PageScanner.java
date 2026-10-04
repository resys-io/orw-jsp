package io.resys.orw.jsp.tester.internal;

import io.resys.orw.jsp.JspParser;
import io.resys.orw.jsp.tree.Jsp;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Parses every page of a web application to find the taglibs it declares and, per taglib uri,
 * the tags and EL functions it uses - what a mock of that library has to provide.
 */
public final class PageScanner {

    /** A tag library as the pages use it. */
    public static final class Library {
        public final String uri;
        /** The prefix it is first declared with, for placeholders. */
        public final String prefix;
        public final Map<String, Boolean> tags = new TreeMap<>();
        /** Function name to its argument count (as first seen). */
        public final Map<String, Integer> functions = new TreeMap<>();
        /** A page declaring it, to resolve the uri relative to. */
        public final Path declaringPage;

        Library(String uri, String prefix, Path declaringPage) {
            this.uri = uri;
            this.prefix = prefix;
            this.declaringPage = declaringPage;
        }
    }

    private static final Pattern FUNCTION_CALL = Pattern.compile("(?<![\\w$.])([A-Za-z_][\\w-]*):([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern EL = Pattern.compile("[$#]\\{((?:[^}'\"]|'[^']*'|\"[^\"]*\")*)}");

    /** Libraries by uri. */
    public final Map<String, Library> libraries = new LinkedHashMap<>();
    /** Per page (web application path, e.g. "/orders.jsp"): prefix to uri, including included files'. */
    public final Map<String, Map<String, String>> taglibsByPage = new HashMap<>();

    public PageScanner scan(Path webapp) throws IOException {
        List<Path> pages;
        try (Stream<Path> files = Files.walk(webapp)) {
            pages = files.filter(f -> f.toString().endsWith(".jsp") || f.toString().endsWith(".jspf")).toList();
        }
        List<SourceFile> documents = JspParser.builder().build()
                .parse(pages, webapp, new InMemoryExecutionContext()).toList();
        for (SourceFile source : documents) {
            if (source instanceof Jsp.Document) {
                Jsp.Document document = (Jsp.Document) source;
                Map<String, String> prefixes = new LinkedHashMap<>();
                Path page = webapp.resolve(document.getSourcePath());
                walk(document.getNodes(), prefixes, page);
                taglibsByPage.put("/" + document.getSourcePath().toString().replace('\\', '/'), prefixes);
            }
        }
        return this;
    }

    private void walk(List<Jsp.Content> nodes, Map<String, String> prefixes, Path page) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Directive) {
                Jsp.Directive directive = (Jsp.Directive) node;
                if ("taglib".equals(directive.getName())) {
                    String prefix = attribute(directive, "prefix");
                    String uri = attribute(directive, "uri");
                    if (prefix != null && uri != null) {
                        prefixes.put(prefix, uri);
                        libraries.computeIfAbsent(uri, u -> new Library(u, prefix, page));
                    }
                }
                if (directive.getIncludedFile() != null) {
                    walk(directive.getIncludedFile().getNodes(), prefixes, page);
                }
            } else if (node instanceof Jsp.Tag) {
                Jsp.Tag tag = (Jsp.Tag) node;
                int colon = tag.getName().indexOf(':');
                Library library = library(prefixes, tag.getName().substring(0, colon));
                if (library != null) {
                    library.tags.put(tag.getName().substring(colon + 1), true);
                }
                for (Jsp.Attribute attribute : tag.getAttributes()) {
                    Matcher el = EL.matcher(attribute.getValue().getValue());
                    while (el.find()) {
                        functions(el.group(1), prefixes);
                    }
                }
                if (tag.getBody() != null) {
                    walk(tag.getBody(), prefixes, page);
                }
            } else if (node instanceof Jsp.ExpressionLanguage) {
                functions(((Jsp.ExpressionLanguage) node).getExpression(), prefixes);
            }
        }
    }

    private void functions(String expression, Map<String, String> prefixes) {
        Matcher call = FUNCTION_CALL.matcher(expression);
        while (call.find()) {
            Library library = library(prefixes, call.group(1));
            if (library != null) {
                library.functions.putIfAbsent(call.group(2), arguments(expression, call.end() - 1));
            }
        }
    }

    private Library library(Map<String, String> prefixes, String prefix) {
        String uri = prefixes.get(prefix);
        return uri == null ? null : libraries.get(uri);
    }

    /**
     * @return how many arguments the call whose ( is at {@code open} passes.
     */
    private static int arguments(String expression, int open) {
        int depth = 0;
        int commas = 0;
        boolean any = false;
        char quote = 0;
        for (int i = open; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                any = true;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (--depth == 0) {
                    return any ? commas + 1 : 0;
                }
            } else if (c == ',' && depth == 1) {
                commas++;
            } else if (!Character.isWhitespace(c) && depth >= 1) {
                any = true;
            }
        }
        return any ? commas + 1 : 0;
    }

    private static String attribute(Jsp.Directive directive, String name) {
        for (Jsp.Attribute attribute : directive.getAttributes()) {
            if (name.equals(attribute.getName())) {
                return attribute.getValue().getValue();
            }
        }
        return null;
    }
}
