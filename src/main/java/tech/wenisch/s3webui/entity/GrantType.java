package tech.wenisch.s3webui.entity;

/**
 * Who a stored S3 credential is handed out to.
 */
public enum GrantType {

    /** Every signed-in user. */
    ALL_AUTHENTICATED,

    /** A single user, matched on their e-mail address. */
    USER,

    /** Everyone carrying a given role (realm or client role from the OIDC token). */
    ROLE,

    /** Everyone in a given group (from the OIDC {@code groups} claim). */
    GROUP
}
