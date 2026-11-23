package tech.wenisch.s3webui.service;

/** A migration job does not exist, or belongs to someone else. */
public class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(String id) {
        super("Migration " + id + " was not found");
    }
}
