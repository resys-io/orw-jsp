package com.acme.shop;

import io.resys.orw.jsp.tester.JspTester;
import io.resys.orw.jsp.tester.MockBehavior;
import io.resys.orw.jsp.tester.RenderRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.ResourceBundle;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the shop's pages with the tester. Each fixture in src/test/fixtures is a test; run with
 * -Dorw.tester.update=true to (re)create their expected output, and to add any mocked tags their
 * pages use to their "mocks".
 */
class OrdersPageTest {

    static final ResourceBundle MESSAGES = ResourceBundle.getBundle("com.acme.shop.MessageResources");

    static final JspTester tester = JspTester.builder()
            .webapp(Path.of("src/main/webapp"))
            // The in-house acme taglib has no implementation here, so it's mocked; its message tag
            // is mocked in Java, for every page and fixture, to show the real text.
            .mock("acme:message", MockBehavior.custom(tag -> MESSAGES.getString(tag.attribute("key"))))
            // What the real in-house footer tag renders (and the migrated Thymeleaf fragment too).
            .mock("acme:footer", MockBehavior.custom(tag ->
                    "<footer class=\"footer\">\u00a9 " + tag.attribute("year") + " ACME</footer>"))
            .build();

    @AfterAll
    static void stop() {
        tester.close();
    }

    @TestFactory
    Stream<DynamicTest> fixtures() {
        return tester.fixtureTests(Path.of("src/test/fixtures"));
    }

    @Test
    void ordersPageProgrammatically() {
        String out = tester.renderOk(RenderRequest.page("/WEB-INF/views/orders.jsp")
                .requestAttribute("orders", List.of()));
        assertTrue(out.contains("<h1>Orders</h1>"), out);
        assertTrue(out.contains("No orders."), out);
        assertTrue(tester.getMockedTaglibs().contains("http://acme.example/tags"));
    }
}
