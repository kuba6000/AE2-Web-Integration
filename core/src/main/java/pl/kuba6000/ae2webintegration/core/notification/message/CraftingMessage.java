package pl.kuba6000.ae2webintegration.core.notification.message;

import java.util.List;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** Detached completion details, including the grid's display name at completion. */
@Desugar
public record CraftingMessage(@NotNull String gridName, @NotNull String cpuName, @NotNull ResourceSnapshot output,
    @NotNull String durationString, boolean wasCancelled, @NotNull List<CraftingRequester> requesters)
    implements IMessage {

    public @NotNull String requesterNames() {
        return requesters.isEmpty() ? "Unknown"
            : requesters.stream()
                .map(
                    requester -> requester.player() != null ? requester.player().name
                        : requester.source() == CraftingRequester.Source.WEB ? "Web" : "Automation")
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
