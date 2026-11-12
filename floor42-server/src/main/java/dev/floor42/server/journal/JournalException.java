package dev.floor42.server.journal;

/** The journal could not be read or written. The company state must then be rebuilt from what was really stored. */
public class JournalException extends RuntimeException {

    public JournalException(String message, Throwable cause) {
        super(message, cause);
    }
}
