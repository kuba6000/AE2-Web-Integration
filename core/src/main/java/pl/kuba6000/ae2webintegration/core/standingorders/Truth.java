package pl.kuba6000.ae2webintegration.core.standingorders;

import org.jetbrains.annotations.NotNull;

/** Three-valued result of evaluating a condition; UNKNOWN means a needed input was not available. */
public enum Truth {

    TRUE,
    FALSE,
    UNKNOWN;

    public static @NotNull Truth of(boolean value) {
        return value ? TRUE : FALSE;
    }

    public @NotNull Truth negate() {
        return switch (this) {
            case TRUE -> FALSE;
            case FALSE -> TRUE;
            case UNKNOWN -> UNKNOWN;
        };
    }
}
