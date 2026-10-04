package io.resys.orw.jsp.tree;

import io.resys.orw.jsp.JspParser;
import lombok.EqualsAndHashCode;
import lombok.Value;
import lombok.With;
import org.jspecify.annotations.Nullable;
import org.openrewrite.marker.Marker;

import java.util.List;
import java.util.UUID;

/**
 * The tag library descriptor (TLD) a {@code <%@ taglib uri="..." %>} {@link Jsp.Directive} resolves
 * to, attached to the directive as a marker by the parser when the TLD could be found (see
 * {@link JspParser} for how). Metadata only: never printed.
 */
@Value
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@With
public class TagLibrary implements Marker {
    @EqualsAndHashCode.Include
    UUID id;

    /**
     * The {@code uri} the directive used.
     */
    String uri;

    /**
     * Where the TLD was loaded from, e.g. {@code src/main/webapp/WEB-INF/app.tld} or
     * {@code lib/jstl.jar!/META-INF/c.tld}.
     */
    String source;

    @Nullable
    String shortName;

    List<TagDescriptor> tags;

    List<FunctionDescriptor> functions;

    public @Nullable TagDescriptor findTag(String name) {
        for (TagDescriptor tag : tags) {
            if (tag.getName().equals(name)) {
                return tag;
            }
        }
        return null;
    }

    public @Nullable FunctionDescriptor findFunction(String name) {
        for (FunctionDescriptor function : functions) {
            if (function.getName().equals(name)) {
                return function;
            }
        }
        return null;
    }

    @Value
    public static class TagDescriptor {
        String name;

        /**
         * {@code empty}, {@code JSP}, {@code scriptless}, or {@code tagdependent}.
         */
        String bodyContent;

        List<AttributeDescriptor> attributes;

        List<VariableDescriptor> variables;

        boolean dynamicAttributes;

        /**
         * Whether a {@code <tei-class>} (TagExtraInfo) may define further variables at translation
         * time, so {@link #getVariables()} may be incomplete.
         */
        boolean extraInfo;

        /**
         * Whether this is a {@code <tag-file>}, whose attributes and body aren't described by the TLD.
         */
        boolean tagFile;

        public @Nullable AttributeDescriptor findAttribute(String name) {
            for (AttributeDescriptor attribute : attributes) {
                if (attribute.getName().equals(name)) {
                    return attribute;
                }
            }
            return null;
        }
    }

    @Value
    public static class AttributeDescriptor {
        String name;
        boolean required;
    }

    /**
     * A scripting variable a tag declares, by a fixed name ({@link #getNameGiven()}) or by the
     * value of one of its attributes ({@link #getNameFromAttribute()}).
     */
    @Value
    public static class VariableDescriptor {
        @Nullable
        String nameGiven;

        @Nullable
        String nameFromAttribute;

        /**
         * {@code NESTED} (only inside the tag's body), {@code AT_BEGIN}, or {@code AT_END}.
         */
        String scope;
    }

    @Value
    public static class FunctionDescriptor {
        String name;
        String signature;
    }
}
