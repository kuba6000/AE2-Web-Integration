package pl.kuba6000.ae2webintegration.core.notification.message;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

/** Detached completion details, including the grid's display name at completion. */
@Desugar
public record CraftingMessage(@NotNull String gridName, String cpuName, String outputItemName, long outputItemAmount,
    String durationString, boolean wasCancelled) implements IMessage {}
