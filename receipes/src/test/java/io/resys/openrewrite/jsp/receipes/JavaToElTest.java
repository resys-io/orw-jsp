package io.resys.openrewrite.jsp.receipes;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JavaToElTest {

    private static final Set<String> VISIBLE = Set.of("user", "count", "done", "name", "items", "a", "b", "c",
            "x", "arr", "i");

    private static String el(String java) {
        JavaToEl.Result result = JavaToEl.translate(java, VISIBLE);
        assertThat(result.failure()).as(java).isNull();
        return result.el();
    }

    private static String failure(String java) {
        JavaToEl.Result result = JavaToEl.translate(java, VISIBLE);
        assertThat(result.el()).as(java).isNull();
        return result.failure();
    }

    @Test
    void gettersAndScopes() {
        assertThat(el("user.getName()")).isEqualTo("user.name");
        assertThat(el("user.getAddress().getCity()")).isEqualTo("user.address.city");
        assertThat(el("user.getURL()")).isEqualTo("user.URL");
        assertThat(el("request.getParameter(\"q\")")).isEqualTo("param.q");
        assertThat(el("request.getParameter(\"first-name\")")).isEqualTo("param['first-name']");
        assertThat(el("session.getAttribute(\"cart\")")).isEqualTo("sessionScope.cart");
        assertThat(el("request.getSession().getAttribute(\"cart\")")).isEqualTo("sessionScope.cart");
        assertThat(el("application.getAttribute(\"config\")")).isEqualTo("applicationScope.config");
        assertThat(el("(java.util.List) request.getAttribute(\"items\")")).isEqualTo("requestScope.items");
        assertThat(el("arr[i]")).isEqualTo("arr[i]");
    }

    @Test
    void operators() {
        assertThat(el("count > 0 && !done")).isEqualTo("count gt 0 and not done");
        assertThat(el("count <= 10 || count >= 20")).isEqualTo("count le 10 or count ge 20");
        assertThat(el("a == null || b != null")).isEqualTo("a == null or b != null");
        assertThat(el("(a || b) && c")).isEqualTo("(a or b) and c");
        assertThat(el("a || b && c")).isEqualTo("a or b and c");
        assertThat(el("x ? \"yes\" : \"no\"")).isEqualTo("x ? 'yes' : 'no'");
        assertThat(el("count - 1")).isEqualTo("count - 1");
        assertThat(el("(count * 2) % 3")).isEqualTo("(count * 2) % 3");
        assertThat(el("-count")).isEqualTo("-count");
        assertThat(el("10L")).isEqualTo("10");
    }

    @Test
    void methodsWithElEquivalents() {
        assertThat(el("name.equals(\"admin\")")).isEqualTo("name == 'admin'");
        assertThat(el("\"admin\".equals(name)")).isEqualTo("'admin' == name");
        assertThat(el("!name.equals(\"admin\")")).isEqualTo("not (name == 'admin')");
        assertThat(el("items.isEmpty()")).isEqualTo("empty items");
        assertThat(el("!items.isEmpty()")).isEqualTo("not empty items");
        assertThat(el("name.toString()")).isEqualTo("name");
        JavaToEl.Result size = JavaToEl.translate("items.size() > 0", VISIBLE);
        assertThat(size.el()).isEqualTo("fn:length(items) gt 0");
        assertThat(size.usesFunctions()).isTrue();
    }

    @Test
    void strings() {
        assertThat(el("\"it's\"")).isEqualTo("'it\\'s'");
        assertThat(el("\"say \\\"hi\\\"\"")).isEqualTo("'say \"hi\"'");
    }

    @Test
    void unsupported() {
        assertThat(failure("secret.getValue()")).isEqualTo("'secret' is a Java variable EL can't see (not mirrored into a page attribute)");
        assertThat(failure("a + b")).contains("'+'");
        assertThat(failure("count / 2")).contains("'/'");
        assertThat(failure("user.isActive()")).contains("isActive");
        assertThat(failure("items.get(0)")).contains("get");
        assertThat(failure("new Date()")).contains("object creation");
        assertThat(failure("x instanceof String")).contains("instanceof");
        assertThat(failure("count++")).contains("++");
        assertThat(failure("count = 1")).contains("assignments");
        assertThat(failure("user.name")).contains("field access");
        assertThat(failure("request")).contains("'request' itself");
    }
}
