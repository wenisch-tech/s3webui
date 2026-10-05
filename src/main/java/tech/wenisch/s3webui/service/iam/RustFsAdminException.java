package tech.wenisch.s3webui.service.iam;

/** An error returned by RustFS's native administration API. */
public class RustFsAdminException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;

    public RustFsAdminException(int statusCode, String errorCode, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
