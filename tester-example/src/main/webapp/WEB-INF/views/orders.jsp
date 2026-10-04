<%@ page contentType="text/html;charset=UTF-8" import="java.util.List, com.acme.shop.Order" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="fn" uri="http://java.sun.com/jsp/jstl/functions" %>
<%@ taglib prefix="bean" uri="http://struts.apache.org/tags-bean" %>
<%@ taglib prefix="logic" uri="http://struts.apache.org/tags-logic" %>
<%@ taglib prefix="html" uri="http://struts.apache.org/tags-html" %>
<%@ taglib prefix="acme" uri="http://acme.example/tags" %>
<html>
<body>
<%@ include file="header.jspf" %>
<% List orders = (List) request.getAttribute("orders"); %>
<h2>Orders (<%= orders.size() %>)</h2>
<table>
<logic:iterate id="order" name="orders" type="com.acme.shop.Order">
  <tr>
    <td><bean:write name="order" property="id"/></td>
    <td>${order.customer.name}<c:if test="${order.customer.vip}"> (VIP)</c:if></td>
    <td><bean:write name="order" property="total"/></td>
    <td>${fn:length(order.lines)} lines</td>
  </tr>
</logic:iterate>
</table>
<% if (orders.isEmpty()) { %>
  <p class="empty">No orders.</p>
<% } %>
<html:form action="/orders/search">
  <html:text property="q" value="<%= request.getParameter("q") == null ? "" : request.getParameter("q") %>"/>
  <html:submit><bean:message key="orders.search"/></html:submit>
</html:form>
<acme:footer year="2026"/>
</body>
</html>
