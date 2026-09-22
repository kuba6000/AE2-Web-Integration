package pl.kuba6000.ae2webintegration.core.notification.message;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

@Desugar
public record CraftingMessage(StableKey grid, String cpuName, String outputItemName, long outputItemAmount,
    String durationString, boolean wasCancelled) implements IMessage {}
