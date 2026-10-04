package io.resys.orw.jsp.tester.internal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The one controller of {@link io.resys.orw.jsp.tester.ThymeleafTester}'s Spring MVC setup: renders
 * the template the request names. The request's attributes (the fixture's) are the model.
 */
@Controller
public class ThymeleafRenderController {

    public static final String PATH = "/__orw_render__";
    public static final String TEMPLATE = ThymeleafRenderController.class.getName() + ".template";

    @RequestMapping(PATH)
    public String render(HttpServletRequest request) {
        return (String) request.getAttribute(TEMPLATE);
    }
}
