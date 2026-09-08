package pl.kuba6000.ae2webintegration.core.identity;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;

/** Stable CPU addresses derived from native identity, without a separate registry. */
public final class CpuIdentity {

    private CpuIdentity() {}

    public static @NotNull StableKey ae2(@NotNull String dimension, int x, int y, int z) {
        return position("cpu:ae2", dimension, x, y, z);
    }

    public static @NotNull StableKey advancedFree(@NotNull String dimension, int x, int y, int z) {
        return position("cpu:advanced_ae:free", dimension, x, y, z);
    }

    public static @NotNull StableKey advanced(@NotNull UUID uuid) {
        return StableKey.create(sink -> {
            StableKey.writeText(sink, "cpu:advanced_ae");
            sink.putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits());
        });
    }

    private static @NotNull StableKey position(@NotNull String kind, @NotNull String dimension, int x, int y, int z) {
        return StableKey.create(sink -> {
            StableKey.writeText(sink, kind);
            StableKey.writeText(sink, dimension);
            sink.putInt(x)
                .putInt(y)
                .putInt(z);
        });
    }
}
