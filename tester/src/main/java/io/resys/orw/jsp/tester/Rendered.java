package io.resys.orw.jsp.tester;

/**
 * The result of rendering a page.
 *
 * @param status      the HTTP status: 200, or 500 if the page failed to compile or threw.
 * @param contentType the response's content type, if any.
 * @param body        the rendered output; for a failure, the error (e.g. the Jasper compilation
 *                    error or the exception's stack trace).
 */
public record Rendered(int status, String contentType, String body) {

    public boolean ok() {
        return status == 200;
    }
}
