package tech.wenisch.s3webui.service;

public class DuplicateBucketException extends RuntimeException {

    public DuplicateBucketException(String message) {
        super(message);
    }
}
