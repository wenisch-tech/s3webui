package tech.wenisch.s3webui.service;

public class MissingS3ConfigurationException extends RuntimeException {

    public MissingS3ConfigurationException(String message) {
        super(message);
    }
}
