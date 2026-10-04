package com.acme.shop;

import io.resys.orw.jsp.tester.JspTester;
import io.resys.orw.jsp.tester.RenderRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the shop's pages with the tester. Each fixture in src/test/fixtures is a test; run with
 * -Dorw.tester.update=true to (re)create their expected output.
 */
class OrdersPageTest {

    static final JspTester tester = JspTester.builder()
            .webapp(Path.of("src/main/webapp"))
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
        assertTrue(out.contains("No orders."), out);
        // The acme taglib has no implementation anywhere: it is mocked automatically.
        assertTrue(tester.getMockedTaglibs().contains("http://acme.example/tags"));
    }
}
