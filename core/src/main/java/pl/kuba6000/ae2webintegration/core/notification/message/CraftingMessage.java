package pl.kuba6000.ae2webintegration.core.notification.message;

import java.util.List;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** Detached completion details, including the grid's display name at completion. */
@Desugar
public record CraftingMessage(@NotNull String gridName, @NotNull String cpuName, @NotNull ResourceSnapshot output,
    @NotNull String durationString, boolean wasCancelled, @NotNull List<CraftingRequester> requesters)
    implements IMessage {

    public @NotNull String requesterNames() {
        return requesters.isEmpty() ? "Unknown"
            : requesters.stream()
                .map(CraftingMessage::requesterName)
                .collect(Collectors.joining(", "));
    }

    private static @NotNull String requesterName(@NotNull CraftingRequester requester) {
        PlayerIdentity player = requester.player();
        if (player != null) return player.name;
        return switch (requester.source()) {
            case WEB -> "Web";
            case STANDING_ORDER -> "Standing order: " + requester.orderName();
            default -> "Automation";
        };
    }
}
