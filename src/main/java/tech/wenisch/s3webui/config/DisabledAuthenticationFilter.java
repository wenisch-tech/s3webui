package tech.wenisch.s3webui.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/** Supplies the virtual administrator used while application authentication is disabled. */
final class DisabledAuthenticationFilter extends OncePerRequestFilter {

    static final String ADMIN_PRINCIPAL = "authentication-disabled@s3webui.local";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        ADMIN_PRINCIPAL,
                        "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        filterChain.doFilter(request, response);
    }
}
