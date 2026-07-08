package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;

class WebEngineTest {

    @TempDir
    File configRoot;

    @Test
    void initInitializesCoreConfigDirectoryFromPlatform() {
        WebEngine.init(new TestPlatform(configRoot), "test-version");

        assertEquals(new File(configRoot, "ae2webintegration"), Config.getConfigDirectory());
        assertEquals(new File(new File(configRoot, "ae2webintegration"), "webdata.json"), Config.getConfigFile("webdata.json"));
    }

    private static class TestPlatform implements IServerPlatform {

        private final File configDirectory;

        TestPlatform(File configDirectory) {
            this.configDirectory = configDirectory;
        }

        @Override
        public UUID getOnlinePlayerUUID(String username) {
            return null;
        }

        @Override
        public UUID getOfflinePlayerUUID(String username) {
            return null;
        }

        @Override
        public File getConfigDirectory() {
            return configDirectory;
        }
    }
}
