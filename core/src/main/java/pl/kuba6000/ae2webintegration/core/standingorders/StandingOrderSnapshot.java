package pl.kuba6000.ae2webintegration.core.standingorders;

import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/**
 * Immutable runtime state of one standing order, safe to read from any thread. Times are epoch milliseconds; a zero
 * time or amount means there is none. The reason and its parameters carry codes and identifiers only, never text,
 * except for the crafting system's own error message.
 *
 * @param status              current status
 * @param reason              why the order has this status where the status alone does not say, or null
 * @param resource            the resource a reason names (AMBIGUOUS_RESOURCE), or null
 * @param path                the member of the definition a reason names (CAPABILITY_UNAVAILABLE), for example
 *                            {@code action.options.lightMode}, or null
 * @param submitError         the crafting system's error message when it refused the job (SUBMIT_REJECTED), in its
 *                            own language, or null
 * @param missing             up to five ingredients the network lacked for the blocked calculation
 *                            (MISSING_INGREDIENTS), largest shortfall first; empty otherwise
 * @param changedAt           when status or reason last changed
 * @param lastSubmittedAmount amount of the last job this order submitted since the server started
 * @param nextCheckAt         when the engine looks at the order next without waiting for new data: cooldown or
 *                            backoff end, otherwise the expected next stock read
 * @param craftingSince       when a running job was submitted or first noticed; set only while crafting
 * @param activeCpuKey        address of the observed CPU crafting the resource, or null when none can be identified
 */
@Desugar
public record StandingOrderSnapshot(@NotNull StandingOrderStatus status, @Nullable StandingOrderReason reason,
    @Nullable StableKey resource, @Nullable String path, @Nullable String submitError,
    @NotNull List<ResourceSnapshot> missing, long changedAt, long lastSubmittedAmount, long nextCheckAt,
    long craftingSince, @Nullable StableKey activeCpuKey) {}
