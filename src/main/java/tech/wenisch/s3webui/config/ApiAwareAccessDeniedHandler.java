package tech.wenisch.s3webui.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;

import java.io.IOException;

/**
 * Answers a rejected {@code /api} call with JSON, and everything else with the access denied page.
 *
 * <p>The page is only mapped for GET, so forwarding a rejected POST to it produced a confusing 405
 * instead of a 403.
 */
class ApiAwareAccessDeniedHandler implements AccessDeniedHandler {

    private final AccessDeniedHandler pageHandler;

    ApiAwareAccessDeniedHandler(String accessDeniedPage) {
        AccessDeniedHandlerImpl handler = new AccessDeniedHandlerImpl();
        handler.setErrorPage(accessDeniedPage);
        this.pageHandler = handler;
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException exception) throws IOException, ServletException {
        if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write("{\"message\":\"Access denied\"}");
            return;
        }
        pageHandler.handle(request, response, exception);
    }
}
