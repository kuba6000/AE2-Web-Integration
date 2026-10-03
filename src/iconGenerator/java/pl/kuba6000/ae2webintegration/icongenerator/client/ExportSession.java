package pl.kuba6000.ae2webintegration.icongenerator.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.icons.export.IMetadataSource;
import pl.kuba6000.ae2webintegration.core.icons.export.IconExportSession;

final class ExportSession {

    private final ClientLevel world;
    private final IconExportSession<IconCandidate> session;

    ExportSession(@NotNull Minecraft minecraft, boolean allowPbo) {
        world = minecraft.level;
        IMetadataSource environment = ExportEnvironment.capture(minecraft);
        IconCatalogue catalogue = new IconCatalogue(minecraft);
        session = new IconExportSession<>(
            minecraft.gameDirectory.toPath()
                .resolve("ae2webicons"),
            environment,
            catalogue,
            new ModernIconRenderer(minecraft, allowPbo));
    }

    boolean tick(@NotNull Minecraft minecraft) {
        if (!isCurrentWorld(minecraft)) cancel("world closed or changed");
        return session.tick();
    }

    boolean isCurrentWorld(@NotNull Minecraft minecraft) {
        return minecraft.level == world;
    }

    void cancel(@NotNull String reason) {
        session.cancel(reason);
    }

    @NotNull
    String status() {
        return session.status();
    }
}
