package io.resys.openrewrite.jsp;

import org.intellij.lang.annotations.Language;
import org.jspecify.annotations.Nullable;
import io.resys.openrewrite.jsp.tree.Jsp;
import org.openrewrite.test.SourceSpec;
import org.openrewrite.test.SourceSpecs;

import java.util.function.Consumer;

public class Assertions {
    private Assertions() {
    }

    public static SourceSpecs jsp(@Language("JSP") @Nullable String before) {
        return jsp(before, s -> {
        });
    }

    public static SourceSpecs jsp(@Language("JSP") @Nullable String before, Consumer<SourceSpec<Jsp.Document>> spec) {
        SourceSpec<Jsp.Document> jsp = new SourceSpec<>(Jsp.Document.class, null, JspParser.builder(), before, null);
        spec.accept(jsp);
        return jsp;
    }

    public static SourceSpecs jsp(@Language("JSP") @Nullable String before, @Language("JSP") @Nullable String after) {
        return jsp(before, after, s -> {
        });
    }

    public static SourceSpecs jsp(@Language("JSP") @Nullable String before, @Language("JSP") @Nullable String after,
                                   Consumer<SourceSpec<Jsp.Document>> spec) {
        SourceSpec<Jsp.Document> jsp = new SourceSpec<>(Jsp.Document.class, null, JspParser.builder(), before, s -> after);
        spec.accept(jsp);
        return jsp;
    }
}
