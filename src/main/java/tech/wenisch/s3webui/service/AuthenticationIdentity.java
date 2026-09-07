package tech.wenisch.s3webui.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Resolves the e-mail address that identifies a user, whether they signed in with the local form or
 * through an OIDC provider. E-mail is the identity everywhere in the application: it is the login
 * name for local users and the join key for {@code USER} credential grants.
 */
public final class AuthenticationIdentity {

    private AuthenticationIdentity() {
    }

    /** The signed-in user's e-mail, or {@code null} when none can be determined. */
    public static String emailOf(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            return firstEmailLike(oidcUser.getEmail(), oidcUser.getPreferredUsername(), authentication.getName());
        }
        return firstEmailLike(authentication.getName());
    }

    /** The first candidate that looks like an e-mail address, or {@code null}. */
    public static String firstEmailLike(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && candidate.contains("@")) {
                return candidate.trim();
            }
        }
        return null;
    }

    /** A human readable label for the current user, falling back to the principal name. */
    public static String displayNameOf(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof OidcUser oidcUser) {
            if (oidcUser.getFullName() != null && !oidcUser.getFullName().isBlank()) {
                return oidcUser.getFullName();
            }
        }
        return authentication.getName();
    }
}
