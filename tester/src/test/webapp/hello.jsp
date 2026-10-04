<%@ page contentType="text/html;charset=UTF-8" %>
<%@ taglib prefix="acme" uri="http://acme.example/tags" %>
<h1>Hello ${name}</h1>
<p><%= request.getParameter("q") %></p>
<acme:panel title="T">inside ${name}</acme:panel>
<acme:menu/>
<p>${acme:upper(name)}</p>
