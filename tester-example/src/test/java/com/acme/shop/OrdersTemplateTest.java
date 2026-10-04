package com.acme.shop;

import io.resys.orw.jsp.tester.RenderRequest;
import io.resys.orw.jsp.tester.ThymeleafTester;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Thymeleaf templates the JSP pages were migrated to (src/main/resources/templates)
 * against the same fixtures and expected output as {@link OrdersPageTest}: each fixture renders
 * the template as Spring MVC would, and must produce the same HTML the original page did.
 */
class OrdersTemplateTest {

    static final ThymeleafTester templates = ThymeleafTester.builder()
            .templates(Path.of("src/main/resources/templates"))
            .messages("com.acme.shop.MessageResources")
            .build();

    @TestFactory
    Stream<DynamicTest> fixtures() {
        return templates.fixtureTests(Path.of("src/test/fixtures"));
    }

    @Test
    void ordersTemplateProgrammatically() {
        String out = templates.renderOk(RenderRequest.page("/WEB-INF/views/orders.jsp")
                .requestAttribute("orders", List.of()));
        assertTrue(out.contains("No orders."), out);
    }
}
