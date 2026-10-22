package pl.kuba6000.ae2webintegration.core.notification.message;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/** Detached completion details, including the grid's display name at completion. */
@Desugar
public record CraftingMessage(@NotNull String gridName, @NotNull String cpuName, @NotNull ResourceSnapshot output,
    @NotNull String durationString, boolean wasCancelled) implements IMessage {}
