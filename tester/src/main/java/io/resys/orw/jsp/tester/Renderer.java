package io.resys.orw.jsp.tester;

import java.util.Set;

/**
 * Renders a page (or template) with a {@link RenderRequest}'s inputs. {@link JspTester} renders
 * the JSP pages and {@link ThymeleafTester} the Thymeleaf templates they're migrated to, from the
 * same requests, so the same fixtures and expected output check both.
 */
public interface Renderer {

    Rendered render(RenderRequest request);

    /**
     * The mocked tags the request's page uses that nothing configures, which updating a fixture
     * adds to its {@code mocks}. None, for renderers without tag mocking.
     */
    default Set<String> unconfiguredMocks(RenderRequest request) {
        return Set.of();
    }

    /**
     * Whether updating fixtures writes this renderer's output as the expected output. Only the
     * reference renderer (the original pages, i.e. {@link JspTester}) should; another renders what
     * a migration produced, which is what's being checked against the reference.
     */
    default boolean writesExpectedOutput() {
        return true;
    }

    /**
     * The comparison to use for a fixture, given the one the fixture asks for. Lets a renderer
     * whose output differs from the reference's only in form (e.g. Thymeleaf vs. JSP) compare as
     * {@link Comparison#HTML}.
     */
    default Comparison comparison(Comparison fixtureComparison) {
        return fixtureComparison;
    }
}
