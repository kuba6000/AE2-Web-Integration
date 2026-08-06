package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;

class CoreEngineTest {

    @TempDir
    File configRoot;

    @Test
    void initInitializesCoreConfigDirectoryFromPlatform() {
        CoreEngine.init(new TestPlatform(configRoot), "test-version", "-forge-1.20.1");

        assertEquals(new File(configRoot, "ae2webintegration"), Config.getConfigDirectory());
        assertEquals(
            new File(new File(configRoot, "ae2webintegration"), "webdata.json"),
            Config.getConfigFile("webdata.json"));
        assertEquals("test-version", CoreEngine.getModVersion());
    }

    @Test
    void exposesSingleCompleteInitializationPath() throws Exception {
        long initMethods = Arrays.stream(CoreEngine.class.getDeclaredMethods())
            .filter(
                method -> method.getName()
                    .equals("init"))
            .count();
        Method loadData = CoreEngine.class.getDeclaredMethod("loadData");

        assertEquals(1, initMethods);
        assertTrue(Modifier.isPrivate(loadData.getModifiers()));
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
        public File getConfigDirectory() {
            return configDirectory;
        }

    }
}
