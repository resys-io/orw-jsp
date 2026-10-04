package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.tree.Jsp;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Tree;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Works out which of a page's Java constructs the automated migration
 * ({@link MigrateJavaVariablesToPageAttributes} with {@code mirrorAll}, then
 * {@link ConvertScriptletsToJstl}) would remove, and for the rest why not and what kind of manual
 * work they need, for {@link FindUnmigratableScriptlets}. It simulates the migration on the page
 * and checks which original nodes survive it as Java.
 */
final class ManualMigrationAnalyzer {

    enum Category {
        DECLARATION("Move the fields/methods into a Java class: a helper bean, or the controller."),
        DATA_ACCESS("Move data access into the controller or a service, and pass the results as model attributes."),
        REQUEST_SESSION_STATE("Move request/session state changes into the controller."),
        RESPONSE_CONTROL("Move redirects, forwards, status codes, headers, and cookies into the controller."),
        OUTPUT_WRITING("Write the markup as template text instead of with out.print()."),
        EXCEPTION_HANDLING("Handle errors in the controller (or an exception handler)."),
        STATIC_CALL("Call it in the controller and pass the result, or expose it to the view as a helper."),
        OBJECT_CREATION("Create the objects in the controller."),
        CONTROL_FLOW("Make the condition/loop expressible in EL (see the reason), or compute it in the controller."),
        VARIABLE("Compute the value in the controller and pass it as a model attribute."),
        EXPRESSION("Compute the value in the controller, or rewrite it with getters EL can read."),
        TAG_ATTRIBUTE("Migrate it together with the tag; or, if the tag evaluates EL, convert it to EL."),
        BLOCK_DELIMITER("It goes with the block it closes or continues; migrate that block."),
        OTHER("Rewrite as JSTL/EL or move into the controller.");

        final String hint;

        Category(String hint) {
            this.hint = hint;
        }
    }

    /**
     * @param node      the construct in the analyzed page (a scriptlet, expression, declaration,
     *                  or the attribute holding a {@code <%= %>}).
     * @param kind      {@code scriptlet}, {@code expression}, {@code declaration}, or {@code tag attribute}.
     * @param code      its Java code.
     */
    record Finding(Tree node, String kind, String code, boolean migratable, List<Category> categories,
                   List<String> reasons) {
    }

    private static final Pattern OUTPUT = Pattern.compile("\\bout\\s*\\.\\s*(?:print|println|write)\\s*\\(");
    private static final Pattern STATE = Pattern.compile(
            "\\b(?:request|session|application|pageContext)\\s*\\.\\s*(?:setAttribute|removeAttribute|invalidate)\\s*\\(|" +
            "\\brequest\\s*\\.\\s*getSession\\s*\\([^)]*\\)\\s*\\.\\s*(?:setAttribute|removeAttribute|invalidate)\\s*\\(");
    private static final Pattern MIRROR = Pattern.compile(
            "pageContext\\s*\\.\\s*setAttribute\\s*\\(\\s*\"([A-Za-z_$][\\w$]*)\"\\s*,\\s*\\1\\s*\\)\\s*;");
    private static final Pattern RESPONSE = Pattern.compile(
            "\\bresponse\\s*\\.\\s*(?:sendRedirect|sendError|setStatus|setHeader|addHeader|setDateHeader|setContentType|" +
            "setCharacterEncoding|addCookie|reset|flushBuffer)\\s*\\(|\\bgetRequestDispatcher\\s*\\(|" +
            "\\bpageContext\\s*\\.\\s*(?:forward|include)\\s*\\(|\\breturn\\s*;");
    private static final Pattern DATA_ACCESS = Pattern.compile(
            "\\b(?:getConnection|prepareStatement|prepareCall|createStatement|executeQuery|executeUpdate|" +
            "createQuery|createNativeQuery|lookup)\\s*\\(|\\b(?:ResultSet|PreparedStatement|Statement|Connection|" +
            "DataSource|InitialContext|EntityManager)\\b|\\b\\w*(?:Dao|DAO|Repository|Service|Manager|Facade)\\s*\\.\\s*\\w+\\s*\\(");
    private static final Pattern EXCEPTIONS = Pattern.compile("\\btry\\s*\\{|\\bcatch\\s*\\(|\\bthrow\\s+");
    private static final Pattern NEW = Pattern.compile("\\bnew\\s+[A-Za-z_$]");
    private static final Pattern CONTROL = Pattern.compile("\\b(?:if|for|while|do|switch)\\b\\s*[({]?");
    private static final Pattern BLOCK_ONLY = Pattern.compile(
            "\\s*}?\\s*(?:else\\s*(?:if\\s*\\(.*\\)\\s*)?\\{?)?\\s*", Pattern.DOTALL);

    private ManualMigrationAnalyzer() {
    }

    static List<Finding> analyze(Jsp.Document document, @Nullable String functionsPrefix) {
        // Simulate step 3 (mirrorAll), then step 4, as the recipes would.
        JavaVariableMigrator variables = new JavaVariableMigrator(false, true);
        List<JavaVariableMigrator.Result> variableResults = variables.analyze(document);
        Jsp.Document mirrored = variables.migrate(document, variableResults);

        Set<String> elVisible = ScriptletToJstlConverter.elVisibleNames(mirrored.getNodes());
        ScriptletToJstlConverter converter = new ScriptletToJstlConverter("c", functionsPrefix, false, true,
                elVisible);
        List<Jsp.Content> converted = converter.convertAssignments(converter.convert(mirrored.getNodes(), elVisible),
                elVisible);

        Set<UUID> remaining = new HashSet<>();
        collectJavaIds(converted, remaining);

        Map<UUID, List<String>> reasonsByNode = new HashMap<>();
        for (ScriptletToJstlConverter.Conversion conversion : converter.conversions) {
            if (!conversion.converted()) {
                reasonsByNode.computeIfAbsent(conversion.node().getId(), k -> new ArrayList<>())
                        .add(conversion.construct() + ": " + conversion.detail());
            }
        }
        Map<String, String> variableReasons = new HashMap<>();
        for (JavaVariableMigrator.Result result : variableResults) {
            if (result.status() == JavaVariableMigrator.Status.SKIPPED) {
                variableReasons.put(result.name(), result.reason());
            }
        }

        List<Finding> findings = new ArrayList<>();
        collectFindings(document.getNodes(), remaining, reasonsByNode, variableReasons,
                ScriptletToJstlConverter.elVisibleNames(converted), findings);
        return findings;
    }

    private static void collectJavaIds(List<Jsp.Content> nodes, Set<UUID> ids) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Scriptlet || node instanceof Jsp.ExpressionScriptlet ||
                node instanceof Jsp.Declaration) {
                ids.add(node.getId());
            } else if (node instanceof Jsp.Tag && ((Jsp.Tag) node).getBody() != null) {
                collectJavaIds(((Jsp.Tag) node).getBody(), ids);
            }
        }
    }

    private static void collectFindings(List<Jsp.Content> nodes, Set<UUID> remaining,
                                        Map<UUID, List<String>> reasonsByNode, Map<String, String> variableReasons,
                                        Set<String> elVisibleAfter, List<Finding> findings) {
        for (Jsp.Content node : nodes) {
            if (node instanceof Jsp.Scriptlet || node instanceof Jsp.ExpressionScriptlet ||
                node instanceof Jsp.Declaration) {
                String kind = node instanceof Jsp.Scriptlet ? "scriptlet" :
                        node instanceof Jsp.ExpressionScriptlet ? "expression" : "declaration";
                String code = code(node);
                boolean migratable = !remaining.contains(node.getId());
                if (migratable) {
                    findings.add(new Finding(node, kind, code, true, List.of(), List.of()));
                    continue;
                }
                List<String> reasons = new ArrayList<>(reasonsByNode.getOrDefault(node.getId(), List.of()));
                if (node instanceof Jsp.Scriptlet) {
                    Set<String> declared = new LinkedHashSet<>();
                    for (JavaStatements.Site site : JavaStatements.sites(code)) {
                        if (site.kind() != JavaStatements.Kind.ASSIGNMENT) {
                            declared.add(site.name());
                        }
                    }
                    for (String name : declared) {
                        String reason = variableReasons.get(name);
                        if (reason != null) {
                            reasons.add("variable '" + name + "': " + reason);
                        }
                    }
                }
                List<Category> categories = classify(node, code);
                if (reasons.isEmpty()) {
                    reasons.add(node instanceof Jsp.Declaration ? "declarations (<%! %>) have no JSTL equivalent" :
                            categories.contains(Category.BLOCK_DELIMITER) ?
                                    "it closes or continues a block that stays Java" :
                                    "it contains Java with no JSTL/EL equivalent");
                }
                findings.add(new Finding(node, kind, code, false, categories, reasons));
            } else if (node instanceof Jsp.Tag) {
                Jsp.Tag tag = (Jsp.Tag) node;
                for (Jsp.Attribute attribute : tag.getAttributes()) {
                    for (String code : JspVariables.expressionsInText(attribute.getValue().getValue())) {
                        JavaToEl.Result el = JavaToEl.translate(code, elVisibleAfter);
                        String reason = "<%= %> in attribute '" + attribute.getName() + "' of <" + tag.getName() + ">" +
                                        (el.ok() ? ", which would be ${" + el.el() + "} if the tag evaluates EL" :
                                                ": " + el.failure());
                        List<Category> categories = new ArrayList<>(List.of(Category.TAG_ATTRIBUTE));
                        categories.addAll(classify(null, code));
                        categories.remove(Category.OTHER);
                        findings.add(new Finding(attribute, "tag attribute", code, false,
                                new ArrayList<>(new LinkedHashSet<>(categories)), List.of(reason)));
                    }
                }
                if (tag.getBody() != null) {
                    collectFindings(tag.getBody(), remaining, reasonsByNode, variableReasons, elVisibleAfter, findings);
                }
            }
        }
    }

    /**
     * Classifies Java code by the kind of manual work it needs, most specific first.
     */
    static List<Category> classify(@Nullable Jsp node, String code) {
        String masked = JspVariables.maskLiteralsAndComments(code);
        // Step 3's own mirrors aren't state changes the page needs.
        String withoutMirrors = MIRROR.matcher(code).replaceAll("");
        String maskedWithoutMirrors = JspVariables.maskLiteralsAndComments(withoutMirrors);
        Map<Category, Boolean> found = new LinkedHashMap<>();
        if (node instanceof Jsp.Declaration) {
            found.put(Category.DECLARATION, true);
        }
        if (DATA_ACCESS.matcher(masked).find()) {
            found.put(Category.DATA_ACCESS, true);
        }
        if (STATE.matcher(maskedWithoutMirrors).find()) {
            found.put(Category.REQUEST_SESSION_STATE, true);
        }
        if (RESPONSE.matcher(masked).find()) {
            found.put(Category.RESPONSE_CONTROL, true);
        }
        if (OUTPUT.matcher(masked).find()) {
            found.put(Category.OUTPUT_WRITING, true);
        }
        if (EXCEPTIONS.matcher(masked).find()) {
            found.put(Category.EXCEPTION_HANDLING, true);
        }
        if (!StaticCalls.find(code, Set.of()).isEmpty()) {
            found.put(Category.STATIC_CALL, true);
        }
        if (NEW.matcher(masked).find()) {
            found.put(Category.OBJECT_CREATION, true);
        }
        if (node instanceof Jsp.Scriptlet) {
            if (BLOCK_ONLY.matcher(masked).matches() && !masked.isBlank()) {
                found.put(Category.BLOCK_DELIMITER, true);
            } else if (CONTROL.matcher(masked).find()) {
                found.put(Category.CONTROL_FLOW, true);
            }
            if (!JavaStatements.sites(withoutMirrors).isEmpty()) {
                found.put(Category.VARIABLE, true);
            }
        }
        if (node instanceof Jsp.ExpressionScriptlet && found.isEmpty()) {
            found.put(Category.EXPRESSION, true);
        }
        if (found.isEmpty()) {
            found.put(Category.OTHER, true);
        }
        return new ArrayList<>(found.keySet());
    }

    static String code(Jsp node) {
        if (node instanceof Jsp.Scriptlet) {
            return ((Jsp.Scriptlet) node).getCodeSource();
        }
        if (node instanceof Jsp.ExpressionScriptlet) {
            return ((Jsp.ExpressionScriptlet) node).getCodeSource();
        }
        return ((Jsp.Declaration) node).getCodeSource();
    }
}
