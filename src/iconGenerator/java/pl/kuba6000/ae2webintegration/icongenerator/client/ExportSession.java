package pl.kuba6000.ae2webintegration.icongenerator.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.icons.export.IconExportSession;

final class ExportSession {

    private final WorldClient world;
    private final IconExportSession<IconCandidate> session;

    ExportSession(@NotNull Minecraft minecraft, boolean allowPbo, boolean animated) {
        world = minecraft.theWorld;
        IconPack.Metadata environment = ExportEnvironment.capture();
        IconCatalogue catalogue = new IconCatalogue(minecraft);
        session = new IconExportSession<>(
            minecraft.mcDataDir.toPath()
                .resolve("ae2webicons"),
            environment,
            catalogue,
            new LegacyIconRenderer(minecraft, allowPbo),
            animated);
    }

    boolean tick(@NotNull Minecraft minecraft) {
        if (!isCurrentWorld(minecraft)) cancel("world closed or changed");
        return session.tick();
    }

    boolean isCurrentWorld(@NotNull Minecraft minecraft) {
        return minecraft.theWorld == world;
    }

    void cancel(@NotNull String reason) {
        session.cancel(reason);
    }

    @NotNull
    String status() {
        return session.status();
    }
}
