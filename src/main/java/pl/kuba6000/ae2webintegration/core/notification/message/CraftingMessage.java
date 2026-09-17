package pl.kuba6000.ae2webintegration.core.notification.message;

import com.github.bsideup.jabel.Desugar;

@Desugar
public record CraftingMessage(pl.kuba6000.ae2webintegration.core.identity.StableKey grid, String cpuName,
    String outputItemName, long outputItemAmount, String durationString, boolean wasCancelled) implements IMessage {}
