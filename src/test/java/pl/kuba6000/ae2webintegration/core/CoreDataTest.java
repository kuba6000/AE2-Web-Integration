package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.lang.reflect.Field;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

class CoreDataTest {

    private static final UUID REGISTERED_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    @TempDir
    File configRoot;

    @BeforeEach
    void setUp() throws Exception {
        Config.init(configRoot);
        AE2Controller.serverPlatform = new TestPlatform(configRoot);
        AE2Controller.AE2Interface = new TestAE();

        Field instance = CoreData.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, new CoreData());
    }

    @Test
    void getPlayerIdUsesRegisteredPlayerUuidInsteadOfOfflineUuid() {
        CoreData.setPassword(REGISTERED_UUID, "hash");

        assertEquals(42, CoreData.getPlayerId("Player"));
    }

    private static class TestPlatform implements IServerPlatform {

        private final File configDirectory;

        TestPlatform(File configDirectory) {
            this.configDirectory = configDirectory;
        }

        @Override
        public UUID getOnlinePlayerUUID(String username) {
            return REGISTERED_UUID;
        }

        @Override
        public UUID getRegisteredPlayerUUID(String username) {
            return REGISTERED_UUID;
        }

        @Override
        public File getConfigDirectory() {
            return configDirectory;
        }
    }

    private static class TestAE implements IAE {

        @Override
        public Iterable<pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid> web$getGrids() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IStackList web$createStackList() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IAEGenericStack web$stackOf(IAEKey key, long amount) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IAEPlayerData web$getPlayerData() {
            return new IAEPlayerData() {
                @Override
                public pl.kuba6000.ae2webintegration.core.api.PlayerIdentity web$getPlayerProfile(int playerId) {
                    return null;
                }

                @Override
                public int web$getPlayerId(UUID id) {
                    return REGISTERED_UUID.equals(id) ? 42 : -1;
                }

                @Override
                public int web$getPlayerId(Object profile) {
                    return -1;
                }
            };
        }
    }
}
