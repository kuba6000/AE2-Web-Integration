package pl.kuba6000.ae2webintegration.core.notification.message;

import com.github.bsideup.jabel.Desugar;

@Desugar
public record StatusMessage(String title, String description, Severity severity) implements IMessage {

    public enum Severity {
        ERROR,
        WARNING,
        NONE
    }
}
