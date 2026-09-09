package pl.kuba6000.ae2webintegration.core.notification.message;

public class ErrorMessage implements IMessage {

    private final String title;
    private final String description;
    private final Severity severity;

    public ErrorMessage(String title, String description, Severity severity) {
        this.title = title;
        this.description = description;
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public Severity getSeverity() {
        return severity;
    }

    public enum Severity {
        ERROR,
        WARNING,
        NONE
    }
}
