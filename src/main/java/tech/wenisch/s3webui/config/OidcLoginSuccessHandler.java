package tech.wenisch.s3webui.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import tech.wenisch.s3webui.entity.AppUser;
import tech.wenisch.s3webui.service.AuthenticationIdentity;
import tech.wenisch.s3webui.service.UserService;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Ties an OIDC login to the local user table.
 *
 * <p>The identity is the e-mail address; a login without one is rejected because credential grants
 * are addressed by e-mail. Administrator status always comes from the database, never from the
 * token, so a realm role called {@code admin} cannot promote anyone. The roles and groups carried by
 * the token are kept as authorities, because credential grants can target them.
 */
@Slf4j
public class OidcLoginSuccessHandler extends SavedRequestAwareAuthenticationSuccessHandler {

    private static final Set<String> APPLICATION_ROLES = Set.of("ROLE_ADMIN", "ROLE_USER");

    private final UserService userService;
    private final boolean createUsers;
    private final HttpSessionSecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public OidcLoginSuccessHandler(UserService userService, boolean createUsers) {
        this.userService = userService;
        this.createUsers = createUsers;
        setDefaultTargetUrl("/");
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        String email = AuthenticationIdentity.emailOf(authentication);
        if (email == null) {
            log.warn("Rejecting OIDC login for '{}': the token carries no e-mail address",
                    authentication.getName());
            reject(request, response);
            return;
        }

        Optional<AppUser> synced = userService.syncOidcUser(
                email, AuthenticationIdentity.displayNameOf(authentication), createUsers);
        if (synced.isEmpty()) {
            log.warn("Rejecting OIDC login for {}: no local account and none was created", email);
            reject(request, response);
            return;
        }

        Authentication roleAware = applyDatabaseRole(authentication, synced.get());
        SecurityContext context = SecurityContextHolder.getContext();
        context.setAuthentication(roleAware);
        securityContextRepository.saveContext(context, request, response);

        super.onAuthenticationSuccess(request, response, roleAware);
    }

    /**
     * Replaces the application roles carried by the token with the role held in the database, while
     * keeping every other authority (provider roles and groups) for credential grant matching.
     */
    private Authentication applyDatabaseRole(Authentication authentication, AppUser user) {
        if (!(authentication instanceof OAuth2AuthenticationToken token)
                || !(authentication.getPrincipal() instanceof OAuth2User principal)) {
            return authentication;
        }

        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (!APPLICATION_ROLES.contains(authority.getAuthority())) {
                authorities.add(authority);
            }
        }
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));

        return new OAuth2AuthenticationToken(principal, authorities, token.getAuthorizedClientRegistrationId());
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        response.sendRedirect(request.getContextPath() + "/login?oidcError=true");
    }
}
