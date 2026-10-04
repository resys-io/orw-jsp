package io.resys.orw.jsp.receipes;

import org.jspecify.annotations.Nullable;
import org.openrewrite.xml.tree.Xml;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The parts of Struts 1 {@code struts-config.xml} files that tell what a page's form bean is:
 * {@code <form-bean>}s and the {@code <action>} mappings that use them.
 */
final class StrutsConfig {

    record FormBean(String name, String type, Map<String, String> properties) {
    }

    record ActionMapping(String path, @Nullable String formBean, @Nullable String attribute, @Nullable String scope) {
    }

    /**
     * What {@code <html:form action="...">} puts in scope: the form bean's attribute name, scope
     * and type.
     */
    record ResolvedForm(String attributeName, String scope, @Nullable FormBean formBean) {
    }

    private final Map<String, FormBean> formBeans = new HashMap<>();
    private final Map<String, ActionMapping> actions = new HashMap<>();

    static boolean isStrutsConfig(Xml.Document document) {
        return "struts-config".equals(document.getRoot().getName());
    }

    void add(Xml.Document document) {
        Xml.Tag root = document.getRoot();
        root.getChild("form-beans").ifPresent(beans -> {
            for (Xml.Tag bean : beans.getChildren("form-bean")) {
                String name = attribute(bean, "name");
                String type = attribute(bean, "type");
                if (name == null || type == null) {
                    continue;
                }
                Map<String, String> properties = new LinkedHashMap<>();
                for (Xml.Tag property : bean.getChildren("form-property")) {
                    String propertyName = attribute(property, "name");
                    String propertyType = attribute(property, "type");
                    if (propertyName != null) {
                        properties.put(propertyName, propertyType == null ? "" : propertyType);
                    }
                }
                formBeans.put(name, new FormBean(name, type, properties));
            }
        });
        root.getChild("action-mappings").ifPresent(mappings -> {
            for (Xml.Tag action : mappings.getChildren("action")) {
                String path = attribute(action, "path");
                if (path != null) {
                    actions.put(path, new ActionMapping(path, attribute(action, "name"),
                            attribute(action, "attribute"), attribute(action, "scope")));
                }
            }
        });
    }

    /**
     * @param action the {@code action} of an {@code <html:form>}, e.g. {@code /save} or {@code /save.do}.
     */
    @Nullable
    ResolvedForm resolve(String action) {
        String path = action;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (path.endsWith(".do")) {
            path = path.substring(0, path.length() - 3);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        ActionMapping mapping = actions.get(path);
        if (mapping == null || mapping.formBean() == null) {
            return null;
        }
        String attributeName = mapping.attribute() != null ? mapping.attribute() : mapping.formBean();
        // Struts 1 keeps form beans in the session unless the mapping says otherwise.
        String scope = mapping.scope() != null ? mapping.scope() : "session";
        return new ResolvedForm(attributeName, scope, formBeans.get(mapping.formBean()));
    }

    private static @Nullable String attribute(Xml.Tag tag, String name) {
        for (Xml.Attribute attribute : tag.getAttributes()) {
            if (attribute.getKeyAsString().equals(name)) {
                return attribute.getValueAsString();
            }
        }
        return null;
    }
}
