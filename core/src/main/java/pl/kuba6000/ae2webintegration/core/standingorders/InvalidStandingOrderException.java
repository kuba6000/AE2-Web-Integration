package pl.kuba6000.ae2webintegration.core.standingorders;

import org.jetbrains.annotations.NotNull;

/**
 * A standing order definition or document that violates the format or a limit. The reason names are stable codes
 * for API responses; the path locates the offending value, for example {@code trigger.children[1].amount}, and is
 * empty for whole-document failures.
 */
public final class InvalidStandingOrderException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        MALFORMED_JSON,
        UNSUPPORTED_VERSION,
        MISSING_FIELD,
        UNKNOWN_FIELD,
        DUPLICATE_FIELD,
        WRONG_TYPE,
        UNKNOWN_KIND,
        INVALID_KEY,
        INVALID_RESOURCE_SLOT,
        INVALID_RESOURCE_TABLE,
        NAME_LENGTH,
        NAME_NOT_TRIMMED,
        NAME_INVALID_CHARACTER,
        COOLDOWN_OUT_OF_RANGE,
        TREE_TOO_DEEP,
        TREE_TOO_LARGE,
        EMPTY_GROUP,
        AMOUNT_NOT_POSITIVE,
        IDLE_CPU_COUNT_OUT_OF_RANGE,
        CAPABILITY_UNAVAILABLE
    }

    private final @NotNull Reason reason;
    private final @NotNull String path;

    public InvalidStandingOrderException(@NotNull Reason reason, @NotNull String path) {
        super(path.isEmpty() ? reason.name() : reason.name() + " at " + path);
        this.reason = reason;
        this.path = path;
    }

    public @NotNull Reason reason() {
        return reason;
    }

    public @NotNull String path() {
        return path;
    }
}
