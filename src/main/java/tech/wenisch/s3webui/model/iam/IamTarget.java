package tech.wenisch.s3webui.model.iam;

import java.util.Locale;

/** A thing a policy can be attached to. */
public record IamTarget(Type type, String name) {

    public enum Type {
        USER,
        GROUP;

        /** Parses the {@code users}/{@code groups} path segment used by the API. */
        public static Type fromPathSegment(String segment) {
            return switch (segment == null ? "" : segment.toLowerCase(Locale.ROOT)) {
                case "users", "user" -> USER;
                case "groups", "group" -> GROUP;
                default -> throw new IllegalArgumentException(
                        "Unknown IAM target '" + segment + "', expected 'users' or 'groups'");
            };
        }
    }

    public static IamTarget user(String name) {
        return new IamTarget(Type.USER, name);
    }

    public static IamTarget group(String name) {
        return new IamTarget(Type.GROUP, name);
    }
}
