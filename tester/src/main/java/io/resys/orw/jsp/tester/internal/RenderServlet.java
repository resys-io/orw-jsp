package io.resys.orw.jsp.tester.internal;

import io.resys.orw.jsp.tester.MockBehavior;
import io.resys.orw.jsp.tester.MockTag;
import io.resys.orw.jsp.tester.RenderRequest;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives the HTTP request for one render (its query string carries the parameters; the
 * {@value #HEADER} header identifies the {@link RenderRequest}), puts the request's attributes in
 * place, and forwards to the page. A failure (e.g. a Jasper compilation error) is returned as a 500
 * with the error's stack trace.
 */
public final class RenderServlet extends HttpServlet {

    public static final String PATH = "/__orw_render__";
    public static final String HEADER = "X-Orw-Render";

    /** A render in progress: the request, and its mock behaviors resolved to {@link MockTag#key}s. */
    public record Pending(RenderRequest request, Map<String, MockBehavior> behaviors) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /**
     * Leaves URLs as they are: the test client sends no cookies, so the container would otherwise
     * add the (random) session id to every URL a page encodes, e.g. Struts' html:form action.
     */
    private static final class NoUrlRewriting extends HttpServletResponseWrapper {
        NoUrlRewriting(HttpServletResponse response) {
            super(response);
        }

        @Override
        public String encodeURL(String url) {
            return url;
        }

        @Override
        public String encodeRedirectURL(String url) {
            return url;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String encodeUrl(String url) {
            return url;
        }

        @Override
        @SuppressWarnings("deprecation")
        public String encodeRedirectUrl(String url) {
            return url;
        }
    }

    public void register(String id, Pending render) {
        pending.put(id, render);
    }

    public void unregister(String id) {
        pending.remove(id);
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Pending render = pending.get(req.getHeader(HEADER));
        if (render == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, "Unknown render");
            return;
        }
        RenderRequest request = render.request();
        ServletContext application = getServletContext();
        request.getRequestAttributes().forEach(req::setAttribute);
        if (!request.getSessionAttributes().isEmpty()) {
            HttpSession session = req.getSession(true);
            request.getSessionAttributes().forEach(session::setAttribute);
        }
        request.getApplicationAttributes().forEach(application::setAttribute);
        req.setAttribute(MockTag.BEHAVIORS, render.behaviors());
        try {
            req.getRequestDispatcher(request.getPage()).forward(req, new NoUrlRewriting(resp));
        } catch (Exception e) {
            if (!resp.isCommitted()) {
                resp.reset();
            }
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.setContentType("text/plain;charset=UTF-8");
            PrintWriter out = resp.getWriter();
            e.printStackTrace(out);
            out.flush();
        } finally {
            request.getApplicationAttributes().keySet().forEach(application::removeAttribute);
            HttpSession session = req.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
    }
}
