package pl.kuba6000.ae2webintegration.core.standingorders;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

/**
 * Immutable runtime state of one standing order, safe to read from any thread. Times are epoch milliseconds; a zero
 * time or amount means there is none.
 *
 * @param status              current status
 * @param message             English detail for the status, or null
 * @param changedAt           when status or message last changed
 * @param lastSubmittedAmount amount of the last job this order submitted since the server started
 * @param nextCheckAt         when the engine looks at the order next without waiting for new data: cooldown or
 *                            backoff end, otherwise the expected next stock read
 * @param craftingSince       when a running job was submitted or first noticed; set only while crafting
 */
@Desugar
public record StandingOrderSnapshot(@NotNull StandingOrderStatus status, @Nullable String message, long changedAt,
    long lastSubmittedAmount, long nextCheckAt, long craftingSince) {}
