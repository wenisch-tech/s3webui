package tech.wenisch.s3webui.service;

public class SecretDecryptionException extends RuntimeException {

    public SecretDecryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
