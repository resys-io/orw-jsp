package io.resys.orw.jsp.tester;

/**
 * Renders a page (or template) with a {@link RenderRequest}'s inputs. {@link JspTester} renders
 * JSP pages; a renderer for the migrated templates (e.g. Thymeleaf) can take the same requests, so
 * the same fixtures check both.
 */
public interface Renderer {

    Rendered render(RenderRequest request);
}
