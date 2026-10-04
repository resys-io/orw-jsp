package io.resys.orw.jsp.receipes;

import io.resys.orw.jsp.receipes.table.JspModelAttributes;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static io.resys.orw.jsp.Assertions.jsp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.openrewrite.xml.Assertions.xml;

class FindModelAttributesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FindModelAttributes());
    }

    private static Tuple row(JspModelAttributes.Row row) {
        return tuple(row.getScope(), row.getName(), row.getType(), row.getProperties(), row.getReadBy());
    }

    @Test
    void scriptletReadsWithTypesAndGetterProperties() {
        rewriteRun(
                spec -> spec.dataTable(JspModelAttributes.Row.class, rows ->
                        assertThat(rows).extracting(FindModelAttributesTest::row).containsExactly(
                                tuple("REQUEST", "user", "com.acme.User", "address.city: String, name",
                                        "request.getAttribute"),
                                tuple("SESSION", "orders", "java.util.List<com.acme.Order>",
                                        "[]: com.acme.Order, [].total", "session.getAttribute"),
                                tuple("PARAMETER", "q", "java.lang.String", "", "request.getParameter"),
                                tuple("APPLICATION", "(non-literal key: Constants.CONFIG_KEY)", "", "",
                                        "application.getAttribute"))),
                jsp(
                        """
                        <%@ page import="java.util.List, com.acme.User, com.acme.Order" %>
                        <%
                            User user = (User) request.getAttribute("user");
                            List<Order> orders = (List<Order>) session.getAttribute("orders");
                            String q = request.getParameter("q");
                            for (Order o : orders) { out.print(o.getTotal()); }
                            String city = user.getAddress().getCity();
                        %>
                        <%= ((User) request.getAttribute("user")).getName() %>
                        <%= application.getAttribute(Constants.CONFIG_KEY) %>
                        <%-- Set by the page itself: not an input, from Java or EL. --%>
                        <% request.setAttribute("msg", "hi"); %>${msg} <%= request.getAttribute("msg") %>
                        """
                )
        );
    }

    @Test
    void elReadsWithPropertyPathsAndIterationElements() {
        rewriteRun(
                spec -> spec.dataTable(JspModelAttributes.Row.class, rows ->
                        assertThat(rows).extracting(FindModelAttributesTest::row).containsExactly(
                                tuple("ANY", "order", "", "lines, lines[], lines[].product.name, lines[].qty", "EL"),
                                tuple("SESSION", "cart", "", "size", "EL"),
                                tuple("PARAMETER", "page", "java.lang.String", "", "EL"),
                                tuple("ANY", "user", "", "email, roles", "EL"),
                                tuple("ANY", "items", "", "", "EL"))),
                jsp(
                        """
                        <c:forEach var="line" items="${order.lines}">${line.product.name} ${line.qty}</c:forEach>
                        <c:set var="total" value="${0}"/>${total}
                        ${sessionScope.cart.size} ${param.page} ${user['email']}
                        ${fn:length(items)} ${not empty user.roles} ${pageScope.local}
                        """
                )
        );
    }

    @Test
    void strutsTagsAndFormBeansFromStrutsConfig() {
        rewriteRun(
                spec -> spec.dataTable(JspModelAttributes.Row.class, rows ->
                        assertThat(rows).extracting(FindModelAttributesTest::row).containsExactly(
                                tuple("REQUEST", "userForm", "com.acme.UserForm", "address.city, countryId, name",
                                        "html:form, struts-config form bean for action /saveUser.do, html:text, html:select"),
                                tuple("ANY", "countries", "", "[].id, [].name", "html:options"),
                                tuple("SESSION", "searchForm", "org.apache.struts.action.DynaActionForm",
                                        "query: java.lang.String",
                                        "html:form, struts-config form bean for action /search, html:text"),
                                tuple("SESSION", "customer", "", "email, orders[]: com.acme.Order, orders[].total",
                                        "logic:present, bean:write, logic:iterate"),
                                tuple("PARAMETER", "mode", "java.lang.String", "", "logic:equal"))),
                xml(
                        """
                        <struts-config>
                            <form-beans>
                                <form-bean name="userForm" type="com.acme.UserForm"/>
                                <form-bean name="searchForm" type="org.apache.struts.action.DynaActionForm">
                                    <form-property name="query" type="java.lang.String"/>
                                </form-bean>
                            </form-beans>
                            <action-mappings>
                                <action path="/saveUser" name="userForm" scope="request" type="com.acme.SaveUserAction"/>
                                <action path="/search" name="searchForm" type="com.acme.SearchAction"/>
                            </action-mappings>
                        </struts-config>
                        """,
                        spec -> spec.path("WEB-INF/struts-config.xml")
                ),
                jsp(
                        """
                        <%@ taglib prefix="html" uri="http://struts.apache.org/tags-html" %>
                        <%@ taglib prefix="bean" uri="http://struts.apache.org/tags-bean" %>
                        <%@ taglib prefix="logic" uri="/WEB-INF/struts-logic.tld" %>
                        <html:form action="/saveUser.do">
                            <html:text property="name"/>
                            <html:text property="address.city"/>
                            <html:select property="countryId">
                                <html:options collection="countries" property="id" labelProperty="name"/>
                            </html:select>
                        </html:form>
                        <html:form action="/search"><html:text property="query"/></html:form>
                        <logic:present name="customer" scope="session">
                            <bean:write name="customer" property="email"/>
                        </logic:present>
                        <logic:iterate id="o" name="customer" property="orders" type="com.acme.Order">
                            <bean:write name="o" property="total"/>
                        </logic:iterate>
                        <bean:define id="title" value="Hello"/><bean:write name="title"/>
                        <logic:equal parameter="mode" value="edit">editing</logic:equal>
                        """,
                        spec -> spec.path("index.jsp")
                )
        );
    }

    @Test
    void formBeanWithoutStrutsConfig() {
        rewriteRun(
                spec -> spec.dataTable(JspModelAttributes.Row.class, rows ->
                        assertThat(rows).extracting(FindModelAttributesTest::row).containsExactly(
                                tuple("ANY", "(form bean of action /save.do)", "", "name", "html:form, html:text"))),
                jsp(
                        """
                        <%@ taglib prefix="html" uri="http://struts.apache.org/tags-html" %>
                        <html:form action="/save.do"><html:text property="name"/></html:form>
                        """
                )
        );
    }

    @Test
    void includesUseBeanAndTiles() {
        rewriteRun(
                spec -> spec.dataTable(JspModelAttributes.Row.class, rows ->
                        assertThat(rows).filteredOn(r -> r.getSourcePath().equals("index.jsp"))
                                .extracting(JspModelAttributes.Row::getScope, JspModelAttributes.Row::getName,
                                        JspModelAttributes.Row::getType, JspModelAttributes.Row::getProperties,
                                        JspModelAttributes.Row::getFiles)
                                .containsExactly(
                                        tuple("SESSION", "cart", "com.acme.Cart", "itemCount", "index.jsp"),
                                        tuple("ANY", "user", "", "name", "header.jspf, index.jsp"),
                                        tuple("TILES", "title", "", "", "index.jsp"))),
                jsp(
                        """
                        <%@ taglib prefix="tiles" uri="http://struts.apache.org/tags-tiles" %>
                        <jsp:useBean id="cart" class="com.acme.Cart" scope="session"/>
                        <jsp:getProperty name="cart" property="itemCount"/>
                        <%@ include file="header.jspf" %>
                        ${user.name}
                        <tiles:getAsString name="title"/>
                        """,
                        spec -> spec.path("index.jsp")
                ),
                jsp("<h1>${user.name}</h1>", spec -> spec.path("header.jspf"))
        );
    }
}
