package pl.kuba6000.ae2webintegration.core;

import java.io.File;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;

/** Real world database and worker lifecycle for completed-crafting integration tests. */
final class CraftingHistoryWorld implements AutoCloseable {

    private final IServerPlatform previousPlatform = AE2Controller.serverPlatform;
    private final IAE previousAe = AE2Controller.AE2Interface;
    private final ConfigTestFixture config;

    CraftingHistoryWorld(File root) {
        config = new ConfigTestFixture(new File(root, "config"));
        CoreEngine.init(new Platform(root));
        Config.INSTANCE.general.port = 0;
        Config.INSTANCE.general.checkForUpdates = false;
        CoreEngine.onServerStarted();
    }

    @Override
    public void close() {
        CoreEngine.onServerStopped();
        AE2Controller.serverPlatform = previousPlatform;
        AE2Controller.AE2Interface = previousAe;
        config.close();
    }

    @Desugar
    private record Platform(File root) implements IServerPlatform {

        @Override
        public java.nio.file.Path getModFile() {
            return null;
        }

        @Override
        public boolean isSingleplayer() {
            return false;
        }

        public @NotNull String getModVersion() {
            return "test";
        }

        public @NotNull String getLoader() {
            return "forge";
        }

        public @NotNull String getMinecraftVersion() {
            return "1.7.10";
        }

        public @NotNull String getIconPackCompatibilityVersion() {
            return "test";
        }

        public @Nullable UUID getOnlinePlayerUUID(String username) {
            return null;
        }

        public @Nullable ILegacyConfigProvider getLegacyConfig() {
            return null;
        }

        public File getConfigDirectory() {
            return new File(root, "config");
        }

        public File getWorldDirectory() {
            return root;
        }
    }
}
