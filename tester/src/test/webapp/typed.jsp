<%@ page import="io.resys.orw.jsp.tester.Person, java.util.List" %>
<% Person p = (Person) request.getAttribute("person"); %>
<p><%= p.getName() %> (<%= p.getAge() %>)</p>
<% List tags = (List) session.getAttribute("tags"); %>
<p><%= tags.size() %> tags, first: ${sessionScope.tags[0]}</p>
<p>${settings.theme}</p>
