package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.tree.Jsp;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out the inputs one page expects but doesn't define itself - model attributes, request
 * parameters, and Tiles attributes - with their type where it can be told and the property paths
 * the page reads from them, for {@link FindModelAttributes}.
 * <p>
 * Everything is text-level (regex) analysis of Java code, EL, and tag attributes, not a real Java
 * or EL parser; see {@link FindModelAttributes} for exactly what is recognized. Statically included
 * files are analyzed as part of the page, in document order, since their reads are the page's
 * inputs too.
 */
final class ModelAttributeCollector {

    enum Scope {
        /** Read from any scope (EL, {@code findAttribute}, Struts tags without {@code scope}). */
        ANY, REQUEST, SESSION, APPLICATION, PARAMETER, TILES
    }

    static final class Attribute {
        final String name;
        Scope scope;
        @Nullable String type;
        /** Property path (e.g. {@code address.city}, {@code orders[].total}, {@code []}) to its type, or "". */
        final Map<String, String> properties = new TreeMap<>();
        final Set<String> evidence = new LinkedHashSet<>();
        final Set<Path> files = new LinkedHashSet<>();

        Attribute(String name, Scope scope) {
            this.name = name;
            this.scope = scope;
        }
    }

    /**
     * A page-local name (an iteration variable, a {@code bean:define}d bean, a Java variable)
     * standing for a property path inside an input.
     */
    private record Alias(Attribute target, String path) {
    }

    private static final String TYPE =
            "(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*[A-Za-z_$][\\w$]*(?:\\s*<[^;=(){}]*>)?(?:\\s*\\[\\s*\\])*";

    private static final String RECEIVER =
            "request\\s*\\.\\s*getSession\\s*\\(\\s*(?:true|false)?\\s*\\)|getServletContext\\s*\\(\\s*\\)|" +
            "session|request|application|pageContext";

    private static final Pattern JAVA_READ = Pattern.compile(
            "(?:\\(\\s*(" + TYPE + ")\\s*\\)\\s*)?\\b(" + RECEIVER + ")\\s*\\.\\s*" +
            "(getAttribute|findAttribute|getParameter|getParameterValues)\\s*\\(\\s*" +
            "(\"(?:[^\"\\\\]|\\\\.)*\"|[^,()]+(?:\\([^()]*\\))?)\\s*(?:,\\s*([^()]+))?\\)");

    private static final Pattern JAVA_SET = Pattern.compile(
            "\\b(?:" + RECEIVER + ")\\s*\\.\\s*setAttribute\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static final Pattern DECLARED_BEFORE = Pattern.compile("(" + TYPE + ")\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*$");

    private static final String GETTER_CHAIN = "((?:\\s*\\.\\s*(?:get|is)[A-Z][\\w$]*\\s*\\(\\s*\\))+)";

    private static final Pattern GETTER = Pattern.compile("\\.\\s*(?:get|is)([A-Z][\\w$]*)\\s*\\(\\s*\\)");

    private static final Pattern FOR_EACH = Pattern.compile(
            "\\bfor\\s*\\(\\s*(?:final\\s+)?(" + TYPE + ")\\s+([A-Za-z_$][\\w$]*)\\s*:\\s*([A-Za-z_$][\\w$]*)" +
            GETTER_CHAIN.replace("+)", "*)") + "\\s*\\)");

    private static final Pattern EL_PATH = Pattern.compile(
            "(?<![\\w$.\\]])([A-Za-z_$][\\w$]*)((?:\\s*\\.\\s*[A-Za-z_$][\\w$]*|\\s*\\[[^\\]]*\\])*)");

    private static final Pattern EL_STRING = Pattern.compile("'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"");

    private static final Pattern EL_LITERAL_KEY = Pattern.compile("\\[\\s*(?:'([A-Za-z_$][\\w$]*)'|\"([A-Za-z_$][\\w$]*)\")\\s*\\]");

    private static final Set<String> EL_RESERVED = Set.of(
            "and", "or", "not", "eq", "ne", "lt", "gt", "le", "ge", "true", "false", "null", "instanceof",
            "empty", "div", "mod", "header", "headerValues", "cookie", "initParam", "pageContext");

    private static final Set<String> EL_SCOPES = Set.of("pageScope", "requestScope", "sessionScope", "applicationScope");

    private static final Pattern STRUTS_LIBRARY = Pattern.compile(
            "(?i)(?:struts[-/._a-z]*?|tags-)(bean|logic|html|nested|tiles)(?:\\.tld)?$|struts-(bean|logic|html|nested|tiles)");

    /** Struts tags whose {@code name} (with {@code property}) names a bean the tag reads. */
    private static final Set<String> STRUTS_BEAN_READERS = Set.of(
            "bean:write", "bean:define", "bean:size",
            "logic:equal", "logic:notEqual", "logic:greaterEqual", "logic:greaterThan", "logic:lessEqual",
            "logic:lessThan", "logic:match", "logic:notMatch", "logic:present", "logic:notPresent",
            "logic:empty", "logic:notEmpty", "logic:iterate",
            "html:link", "html:rewrite", "html:img", "html:optionsCollection");

    /** Struts {@code html:} input tags whose {@code property} is a property of the form bean. */
    private static final Set<String> STRUTS_FIELDS = Set.of(
            "text", "password", "hidden", "textarea", "checkbox", "multibox", "radio", "select", "file");

    private final Path pageFile;
    private final @Nullable StrutsConfig strutsConfig;

    private final Map<String, Attribute> attributes = new LinkedHashMap<>();
    private final Map<String, Alias> elAliases = new HashMap<>();
    private final Set<String> elLocals = new LinkedHashSet<>();
    private final Map<String, Alias> javaAliases = new HashMap<>();
    private final Map<String, String> libraryByPrefix = new HashMap<>();
    private final Map<String, String> imports = new HashMap<>();
    private final List<String> javaCode = new ArrayList<>();
    private final Set<String> pageSetAttributes = new LinkedHashSet<>();

    private Path file;
    /** The form bean of the innermost enclosing {@code <html:form>}, if any. */
    private @Nullable Attribute form;

    ModelAttributeCollector(Path pageFile, @Nullable StrutsConfig strutsConfig) {
        this.pageFile = pageFile;
        this.file = pageFile;
        this.strutsConfig = strutsConfig;
    }

    Collection<Attribute> collect(Jsp.Document document) {
        walk(document.getNodes());
        resolveJavaPropertyReads();
        for (Attribute attribute : attributes.values()) {
            if (attribute.type != null) {
                attribute.type = qualify(attribute.type);
            }
            attribute.properties.replaceAll((path, type) -> type.isEmpty() ? type : qualify(type));
        }
        return attributes.values();
    }

    // -----------------------------------------------------------------------------------------
    // Walking the page
    // -----------------------------------------------------------------------------------------

    private void walk(List<Jsp.Content> nodes) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Directive) {
                visitDirective((Jsp.Directive) node);
            } else if (node instanceof Jsp.Scriptlet) {
                visitJava(((Jsp.Scriptlet) node).getCode());
            } else if (node instanceof Jsp.Declaration) {
                visitJava(((Jsp.Declaration) node).getCode());
            } else if (node instanceof Jsp.ExpressionScriptlet) {
                visitJava(((Jsp.ExpressionScriptlet) node).getCode());
            } else if (node instanceof Jsp.ExpressionLanguage) {
                visitEl(((Jsp.ExpressionLanguage) node).getExpression());
            } else if (node instanceof Jsp.Tag) {
                visitTag((Jsp.Tag) node);
            }
        }
    }

    private void visitDirective(Jsp.Directive directive) {
        String name = directive.getName();
        if ("taglib".equals(name)) {
            String prefix = attribute(directive.getAttributes(), "prefix");
            String uri = attribute(directive.getAttributes(), "uri");
            if (prefix != null && uri != null) {
                libraryByPrefix.put(prefix, uri);
            }
        } else if ("page".equals(name)) {
            String importList = attribute(directive.getAttributes(), "import");
            if (importList != null) {
                for (String entry : importList.split(",")) {
                    String className = entry.trim();
                    int dot = className.lastIndexOf('.');
                    if (dot > 0 && !className.endsWith(".*")) {
                        imports.put(className.substring(dot + 1), className);
                    }
                }
            }
        }
        Jsp.IncludedFile includedFile = directive.getIncludedFile();
        if (includedFile != null) {
            Path saved = file;
            file = includedFile.getSourcePath();
            walk(includedFile.getNodes());
            file = saved;
        }
    }

    private void visitTag(Jsp.Tag tag) {
        String name = tag.getName();
        int colon = name.indexOf(':');
        String prefix = name.substring(0, colon);
        String local = name.substring(colon + 1);

        // Reads in attribute values (EL and <%= %>) come first: they are evaluated before the tag
        // defines anything.
        for (Jsp.Attribute attribute : tag.getAttributes()) {
            String value = attribute.getValue().getValue();
            JspVariables.elInText(value).forEach(this::visitEl);
            JspVariables.expressionsInText(value).forEach(this::visitJava);
        }

        String kind = strutsKind(prefix);
        Attribute enclosingForm = form;
        if (kind != null) {
            visitStrutsTag(tag, kind + ":" + local);
        } else if ("jsp".equals(prefix)) {
            visitStandardAction(tag, local);
        } else {
            visitOtherTag(tag, local);
        }

        if (tag.getBody() != null) {
            walk(tag.getBody());
        }
        form = enclosingForm;
    }

    private void visitStrutsTag(Jsp.Tag tag, String name) {
        Map<String, String> a = literalAttributes(tag);
        String scopeName = a.get("scope");
        Scope scope = scopeName == null ? Scope.ANY : scopeOf(scopeName);

        if ("html:form".equals(name)) {
            visitHtmlForm(a.get("action"));
            return;
        }
        if (name.startsWith("tiles:")) {
            visitTilesTag(name, a);
            return;
        }
        if (name.startsWith("logic:") && a.containsKey("parameter")) {
            read(a.get("parameter"), Scope.PARAMETER, name).type = "java.lang.String";
        }
        if ("bean:parameter".equals(name) && a.containsKey("name")) {
            read(a.get("name"), Scope.PARAMETER, name).type = "java.lang.String";
            if (a.containsKey("id")) {
                elLocals.add(a.get("id"));
            }
            return;
        }

        String property = a.get("property");
        if ("html:options".equals(name) && a.containsKey("collection")) {
            // <html:options collection="x" property="value" labelProperty="label"/>: elements of x.
            Attribute collection = read(a.get("collection"), scope, name);
            addProperty(collection, "[]." + strutsPath(property == null ? "value" : property), "");
            if (a.containsKey("labelProperty")) {
                addProperty(collection, "[]." + strutsPath(a.get("labelProperty")), "");
            }
            return;
        }

        String beanName = a.get("name");
        Attribute target = null;
        String path = "";
        if (beanName != null && (STRUTS_BEAN_READERS.contains(name) ||
                                 (name.startsWith("html:") && STRUTS_FIELDS.contains(name.substring(5))))) {
            Alias alias = elAliases.get(beanName);
            if (alias != null) {
                target = alias.target();
                path = alias.path();
            } else if (!elLocals.contains(beanName)) {
                target = read(beanName, scope, name);
            }
        } else if (beanName == null && name.startsWith("html:") &&
                   (STRUTS_FIELDS.contains(name.substring(5)) || "html:optionsCollection".equals(name))) {
            target = form;
        }
        if (target != null) {
            target.evidence.add(name);
            target.files.add(file);
        }
        String propertyPath = target == null ? null : join(path, property == null ? null : strutsPath(property));

        switch (name) {
            case "logic:iterate" -> {
                // <logic:iterate id="item" name="items" [property="list"] [type="com.x.Item"]>
                String id = a.get("id");
                if (id != null && target != null) {
                    String elements = join(propertyPath, "[]");
                    elAliases.put(id, new Alias(target, elements));
                    addProperty(target, elements, a.getOrDefault("type", ""));
                } else if (id != null) {
                    elLocals.add(id);
                }
            }
            case "bean:define" -> {
                // <bean:define id="x" name="bean" [property="p"] [type="T"]/>, or value="..."
                String id = a.get("id");
                if (id != null && target != null) {
                    elAliases.put(id, new Alias(target, propertyPath));
                    if (a.containsKey("type")) {
                        if (propertyPath.isEmpty()) {
                            target.type = a.get("type");
                        } else {
                            addProperty(target, propertyPath, a.get("type"));
                        }
                    }
                } else if (id != null) {
                    elLocals.add(id);
                }
            }
            case "bean:size" -> {
                if (a.containsKey("id")) {
                    elLocals.add(a.get("id"));
                }
                if (target != null && propertyPath != null && !propertyPath.isEmpty()) {
                    addProperty(target, propertyPath, "");
                }
            }
            case "html:optionsCollection" -> {
                // Elements of the bean's collection property, with label/value properties.
                if (target != null && propertyPath != null) {
                    String elements = join(propertyPath, "[]");
                    addProperty(target, join(elements, a.getOrDefault("label", "label")), "");
                    addProperty(target, join(elements, a.getOrDefault("value", "value")), "");
                }
            }
            default -> {
                if (target != null && propertyPath != null && !propertyPath.isEmpty()) {
                    addProperty(target, propertyPath, "");
                }
            }
        }
    }

    private void visitHtmlForm(@Nullable String action) {
        if (action == null) {
            return;
        }
        StrutsConfig.ResolvedForm resolved = strutsConfig == null ? null : strutsConfig.resolve(action);
        if (resolved != null) {
            form = read(resolved.attributeName(), scopeOf(resolved.scope()), "html:form");
            form.evidence.add("struts-config form bean for action " + action);
            if (resolved.formBean() != null) {
                form.type = resolved.formBean().type();
                resolved.formBean().properties().forEach((property, type) -> addProperty(form, property, type));
            }
        } else {
            form = read("(form bean of action " + action + ")", Scope.ANY, "html:form");
        }
    }

    private void visitTilesTag(String name, Map<String, String> a) {
        switch (name) {
            case "tiles:getAsString" -> {
                if (a.containsKey("name")) {
                    read(a.get("name"), Scope.TILES, name);
                }
            }
            case "tiles:insert" -> {
                if (a.containsKey("attribute")) {
                    read(a.get("attribute"), Scope.TILES, name);
                }
            }
            case "tiles:useAttribute", "tiles:importAttribute" -> {
                if (a.containsKey("name")) {
                    Attribute attribute = read(a.get("name"), Scope.TILES, name);
                    if (a.containsKey("classname")) {
                        attribute.type = a.get("classname");
                    }
                    String id = a.getOrDefault("id", a.get("name"));
                    elAliases.put(id, new Alias(attribute, ""));
                } else {
                    read("(all Tiles attributes)", Scope.TILES, name);
                }
            }
            default -> {
                // Other Tiles tags define rather than read.
            }
        }
    }

    private void visitStandardAction(Jsp.Tag tag, String local) {
        Map<String, String> a = literalAttributes(tag);
        if ("useBean".equals(local) && a.containsKey("id")) {
            String scope = a.getOrDefault("scope", "page");
            String type = a.containsKey("type") ? a.get("type") : a.get("class");
            if ("page".equals(scope)) {
                elLocals.add(a.get("id"));
            } else {
                Attribute bean = read(a.get("id"), scopeOf(scope), "jsp:useBean");
                if (type != null) {
                    bean.type = type;
                }
                elAliases.put(a.get("id"), new Alias(bean, ""));
                javaAliases.put(a.get("id"), new Alias(bean, ""));
            }
        } else if (("getProperty".equals(local) || "setProperty".equals(local)) && a.containsKey("name")) {
            Alias alias = elAliases.get(a.get("name"));
            Attribute target = alias != null ? alias.target() :
                    elLocals.contains(a.get("name")) ? null : read(a.get("name"), Scope.ANY, "jsp:" + local);
            String property = a.get("property");
            if (target != null && property != null && !"*".equals(property)) {
                addProperty(target, join(alias == null ? "" : alias.path(), property), "");
            }
        }
    }

    private void visitOtherTag(Jsp.Tag tag, String local) {
        Map<String, String> a = literalAttributes(tag);
        String var = a.get("var");
        if (var == null) {
            return;
        }
        if ("forEach".equals(local)) {
            // <c:forEach var="item" items="${x.list}">: item stands for the elements.
            String items = tagAttribute(tag, "items");
            Alias collection = items == null ? null : elAlias(items);
            if (collection != null) {
                String elements = join(collection.path(), "[]");
                elAliases.put(var, new Alias(collection.target(), elements));
                addProperty(collection.target(), elements, "");
            } else {
                elLocals.add(var);
            }
        } else {
            elLocals.add(var);
        }
        if (a.containsKey("varStatus")) {
            elLocals.add(a.get("varStatus"));
        }
    }

    // -----------------------------------------------------------------------------------------
    // EL
    // -----------------------------------------------------------------------------------------

    private void visitEl(String expression) {
        for (ElRead read : elReads(expression)) {
            resolveEl(read);
        }
    }

    private record ElRead(String root, List<String> segments) {
    }

    /**
     * @return the input an EL expression consisting of a single path (e.g. {@code ${order.lines}})
     * refers to, if any, as an alias.
     */
    private @Nullable Alias elAlias(String attributeValue) {
        List<String> bodies = JspVariables.elInText(attributeValue);
        if (bodies.size() != 1) {
            return null;
        }
        List<ElRead> reads = elReads(bodies.get(0));
        if (reads.size() != 1) {
            return null;
        }
        return resolveEl(reads.get(0));
    }

    /**
     * Records an EL read, returning what it refers to (or {@code null} for page-local names).
     */
    private @Nullable Alias resolveEl(ElRead read) {
        String root = read.root();
        List<String> segments = read.segments();
        Scope scope = Scope.ANY;
        if ("pageScope".equals(root)) {
            return null; // the page's own
        } else if (EL_SCOPES.contains(root)) {
            if (segments.isEmpty() || segments.get(0).equals("[]")) {
                return null;
            }
            scope = scopeOf(root.substring(0, root.length() - "Scope".length()));
            root = segments.get(0);
            segments = segments.subList(1, segments.size());
        } else if ("param".equals(root) || "paramValues".equals(root)) {
            if (!segments.isEmpty() && !segments.get(0).equals("[]")) {
                read(segments.get(0), Scope.PARAMETER, "EL").type =
                        "param".equals(root) ? "java.lang.String" : "java.lang.String[]";
            }
            return null;
        }

        String path = String.join(".", segments).replace(".[]", "[]");
        if (scope == Scope.ANY) {
            Alias alias = elAliases.get(root);
            if (alias != null) {
                String full = join(alias.path(), path);
                if (!full.isEmpty()) {
                    addProperty(alias.target(), full, "");
                }
                alias.target().files.add(file);
                return new Alias(alias.target(), full);
            }
            if (elLocals.contains(root)) {
                return null;
            }
        }
        Attribute attribute = read(root, scope, "EL");
        if (!path.isEmpty()) {
            addProperty(attribute, path, "");
        }
        return new Alias(attribute, path);
    }

    private static List<ElRead> elReads(String expression) {
        // ['key'] -> .key; then blank out remaining string literals.
        String normalized = EL_LITERAL_KEY.matcher(expression).replaceAll(".$1$2");
        normalized = EL_STRING.matcher(normalized).replaceAll(m -> " ".repeat(m.group().length()));
        List<ElRead> reads = new ArrayList<>();
        Matcher m = EL_PATH.matcher(normalized);
        while (m.find()) {
            String root = m.group(1);
            int after = skipWhitespace(normalized, m.end());
            boolean call = after < normalized.length() && normalized.charAt(after) == '(';
            boolean functionPrefix = after < normalized.length() && normalized.charAt(after) == ':' &&
                                     after + 1 < normalized.length() &&
                                     Character.isJavaIdentifierStart(normalized.charAt(after + 1));
            int before = m.start() - 1;
            while (before >= 0 && Character.isWhitespace(normalized.charAt(before))) {
                before--;
            }
            boolean functionName = before >= 0 && normalized.charAt(before) == ':' && before > 0 &&
                                   Character.isJavaIdentifierPart(normalized.charAt(before - 1));
            if (EL_RESERVED.contains(root) || functionPrefix || functionName ||
                (call && m.group(2).isEmpty())) {
                continue;
            }
            List<String> segments = new ArrayList<>();
            Matcher segment = Pattern.compile("\\.\\s*([A-Za-z_$][\\w$]*)|\\[[^\\]]*\\]").matcher(m.group(2));
            while (segment.find()) {
                segments.add(segment.group(1) != null ? segment.group(1) : "[]");
            }
            if (call && !segments.isEmpty()) {
                segments.remove(segments.size() - 1); // a method call, not a property
            }
            reads.add(new ElRead(root, segments));
        }
        return reads;
    }

    // -----------------------------------------------------------------------------------------
    // Java
    // -----------------------------------------------------------------------------------------

    private void visitJava(String code) {
        javaCode.add(code);
        // Attributes the page sets itself are not inputs from then on.
        List<int[]> setPositions = new ArrayList<>();
        List<String> setNames = new ArrayList<>();
        Matcher set = JAVA_SET.matcher(code);
        while (set.find()) {
            setPositions.add(new int[]{set.start()});
            setNames.add(set.group(1));
        }
        Matcher m = JAVA_READ.matcher(code);
        while (m.find()) {
            String key = m.group(4).trim();
            if (key.startsWith("\"")) {
                String literal = key.substring(1, key.length() - 1);
                boolean setBefore = pageSetAttributes.contains(literal);
                for (int i = 0; i < setNames.size() && !setBefore; i++) {
                    setBefore = setNames.get(i).equals(literal) && setPositions.get(i)[0] < m.start();
                }
                if (setBefore && !m.group(3).startsWith("getParameter")) {
                    continue;
                }
            }
            readJava(code, m);
        }
        pageSetAttributes.addAll(setNames);
        elLocals.addAll(setNames);
    }

    /**
     * Records one {@link #JAVA_READ} match: the input, its type from a cast or the declared type of
     * the variable it's assigned to, and that variable as an alias for later property reads.
     */
    private void readJava(String code, Matcher m) {
        String cast = m.group(1);
        String receiver = m.group(2).replaceAll("\\s", "");
        String method = m.group(3);
        String key = m.group(4).trim();
        String scopeArgument = m.group(5);
        boolean literal = key.startsWith("\"");
        String name = literal ? key.substring(1, key.length() - 1) : "(non-literal key: " + key + ")";
        String evidence = receiver.replaceAll("\\(.*", "()") + "." + method;

        Attribute attribute;
        if (method.startsWith("getParameter")) {
            attribute = read(name, Scope.PARAMETER, evidence);
            attribute.type = "getParameter".equals(method) ? "java.lang.String" : "java.lang.String[]";
        } else {
            if ("pageContext".equals(receiver) && "getAttribute".equals(method) && scopeArgument == null) {
                return; // page scope: the page's own
            }
            Scope scope = switch (receiver) {
                case "request" -> Scope.REQUEST;
                case "application", "getServletContext()" -> Scope.APPLICATION;
                case "pageContext" -> scopeArgument == null ? Scope.ANY : scopeOfConstant(scopeArgument);
                default -> Scope.SESSION;
            };
            attribute = read(name, scope, evidence);
        }

        String declaredType = null;
        String variable = null;
        Matcher declared = DECLARED_BEFORE.matcher(code.substring(Math.max(0, m.start() - 200), m.start()));
        if (declared.find()) {
            declaredType = declared.group(1);
            variable = declared.group(2);
        }
        String type = cast != null ? cast : declaredType;
        if (type != null && !"Object".equals(type) && !"java.lang.Object".equals(type) &&
            !method.startsWith("getParameter")) {
            attribute.type = type.replaceAll("\\s+", "");
        }
        if (variable != null) {
            javaAliases.put(variable, new Alias(attribute, ""));
        }
        // ((User) request.getAttribute("user")).getName()
        if (cast != null && code.substring(0, m.start()).matches("(?s).*\\(\\s*$")) {
            Matcher chain = Pattern.compile("^\\s*\\)" + GETTER_CHAIN).matcher(code.substring(m.end()));
            if (chain.find()) {
                addProperty(attribute, getterPath(chain.group(1)), "");
            }
        }
    }

    /**
     * Property reads through Java variables holding an input: getter chains, for-each loops over
     * them, and typed assignments from getters.
     */
    private void resolveJavaPropertyReads() {
        String code = String.join("\n", javaCode);
        // For-each loops first: they create more aliases.
        boolean added = true;
        while (added) {
            added = false;
            Matcher loop = FOR_EACH.matcher(code);
            while (loop.find()) {
                Alias collection = javaAliases.get(loop.group(3));
                String variable = loop.group(2);
                if (collection != null && !javaAliases.containsKey(variable)) {
                    String chain = loop.group(4) == null ? "" : getterPath(loop.group(4));
                    String elements = join(join(collection.path(), chain), "[]");
                    addProperty(collection.target(), elements, loop.group(1).replaceAll("\\s+", ""));
                    javaAliases.put(variable, new Alias(collection.target(), elements));
                    added = true;
                }
            }
        }
        for (Map.Entry<String, Alias> entry : javaAliases.entrySet()) {
            Alias alias = entry.getValue();
            Matcher use = Pattern.compile("(?:(" + TYPE + ")\\s+[A-Za-z_$][\\w$]*\\s*=\\s*)?(?<![\\w$.])" +
                                          Pattern.quote(entry.getKey()) + GETTER_CHAIN).matcher(code);
            while (use.find()) {
                String type = use.group(1);
                boolean whole = type != null && code.substring(use.end()).matches("(?s)^\\s*;.*");
                addProperty(alias.target(), join(alias.path(), getterPath(use.group(2))),
                        whole ? type.replaceAll("\\s+", "") : "");
            }
        }
    }

    private static String getterPath(String chain) {
        List<String> properties = new ArrayList<>();
        Matcher getter = GETTER.matcher(chain);
        while (getter.find()) {
            properties.add(decapitalize(getter.group(1)));
        }
        return String.join(".", properties);
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    /**
     * Records a read of an input, merging reads from "any" scope with reads from a specific one.
     */
    private Attribute read(String name, Scope scope, String evidence) {
        Attribute attribute;
        if (scope == Scope.PARAMETER || scope == Scope.TILES) {
            attribute = attributes.computeIfAbsent(scope + ":" + name, k -> new Attribute(name, scope));
        } else {
            attribute = null;
            for (Scope existing : List.of(Scope.ANY, Scope.REQUEST, Scope.SESSION, Scope.APPLICATION)) {
                Attribute candidate = attributes.get(existing + ":" + name);
                if (candidate != null && (scope == Scope.ANY || existing == scope || existing == Scope.ANY)) {
                    attribute = candidate;
                    break;
                }
            }
            if (attribute == null) {
                attribute = new Attribute(name, scope);
                attributes.put(scope + ":" + name, attribute);
            } else if (attribute.scope == Scope.ANY && scope != Scope.ANY) {
                // A specific scope learned for an attribute so far only read from any scope.
                attributes.remove(Scope.ANY + ":" + name);
                attribute.scope = scope;
                attributes.put(scope + ":" + name, attribute);
            }
        }
        attribute.evidence.add(evidence);
        attribute.files.add(file);
        return attribute;
    }

    private static void addProperty(Attribute attribute, @Nullable String path, String type) {
        if (path == null || path.isEmpty()) {
            return;
        }
        String existing = attribute.properties.get(path);
        if (existing == null || existing.isEmpty()) {
            attribute.properties.put(path, type);
        }
    }

    private String qualify(String type) {
        Matcher m = Pattern.compile("(?<![\\w$.])([A-Za-z_$][\\w$]*)").matcher(type);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String qualified = imports.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(qualified == null ? m.group(1) : qualified));
        }
        m.appendTail(out);
        return out.toString();
    }

    private @Nullable String strutsKind(String prefix) {
        String uri = libraryByPrefix.get(prefix);
        if (uri == null) {
            return null;
        }
        Matcher m = STRUTS_LIBRARY.matcher(uri);
        if (!m.find()) {
            return null;
        }
        return (m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase(Locale.ROOT);
    }

    /**
     * Struts property expressions: {@code a.b}, indexed {@code items[0].name} or mapped
     * {@code map(key)}; indexes become {@code []}.
     */
    private static String strutsPath(String property) {
        return property.replaceAll("\\[[^\\]]*\\]", "[]").replaceAll("\\([^)]*\\)", "");
    }

    private static @Nullable String join(@Nullable String path, @Nullable String more) {
        if (path == null) {
            return more;
        }
        if (more == null || more.isEmpty()) {
            return path;
        }
        if (path.isEmpty()) {
            return more;
        }
        return more.startsWith("[]") ? path + more : path + "." + more;
    }

    private static Scope scopeOf(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "request" -> Scope.REQUEST;
            case "session" -> Scope.SESSION;
            case "application" -> Scope.APPLICATION;
            default -> Scope.ANY;
        };
    }

    private static Scope scopeOfConstant(String argument) {
        String constant = argument.trim();
        if (constant.endsWith("REQUEST_SCOPE")) {
            return Scope.REQUEST;
        }
        if (constant.endsWith("SESSION_SCOPE")) {
            return Scope.SESSION;
        }
        if (constant.endsWith("APPLICATION_SCOPE")) {
            return Scope.APPLICATION;
        }
        return Scope.ANY;
    }

    private static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
            return name; // JavaBeans: getURL -> URL
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static int skipWhitespace(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /**
     * @return the tag's attributes whose values are literal (no EL or {@code <%= %>}).
     */
    private static Map<String, String> literalAttributes(Jsp.Tag tag) {
        Map<String, String> literal = new HashMap<>();
        for (Jsp.Attribute attribute : tag.getAttributes()) {
            String value = attribute.getValue().getValue();
            if (!value.contains("${") && !value.contains("#{") && !value.contains("<%")) {
                literal.put(attribute.getName(), value);
            }
        }
        return literal;
    }

    private static @Nullable String tagAttribute(Jsp.Tag tag, String name) {
        return attribute(tag.getAttributes(), name);
    }

    private static @Nullable String attribute(List<Jsp.Attribute> attributes, String name) {
        for (Jsp.Attribute attribute : attributes) {
            if (name.equals(attribute.getName())) {
                return attribute.getValue().getValue();
            }
        }
        return null;
    }
}
