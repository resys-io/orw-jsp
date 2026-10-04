package io.resys.orw.jsp.tester;

import java.util.Set;

/**
 * Renders a page (or template) with a {@link RenderRequest}'s inputs. {@link JspTester} renders
 * JSP pages; a renderer for the migrated templates (e.g. Thymeleaf) can take the same requests, so
 * the same fixtures check both.
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
}
