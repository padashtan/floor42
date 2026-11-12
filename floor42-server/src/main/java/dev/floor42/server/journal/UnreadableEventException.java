package dev.floor42.server.journal;

/** One stored event that this version cannot decode. Its message never quotes the payload. */
public final class UnreadableEventException extends JournalException {

    private final Unreadable.Reason reason;
    private final String detail;

    public UnreadableEventException(Unreadable.Reason reason, String type, String detail, Throwable cause) {
        super("Cannot decode " + type + ": " + detail, cause);
        this.reason = reason;
        this.detail = detail;
    }

    public Unreadable.Reason reason() {
        return reason;
    }

    public String detail() {
        return detail;
    }
}
