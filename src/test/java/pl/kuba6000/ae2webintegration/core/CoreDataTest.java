package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

class CoreDataTest {

    private static final UUID REGISTERED_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final PlayerIdentity REGISTERED_PLAYER = new PlayerIdentity(REGISTERED_UUID, "Player");
    @TempDir
    File configRoot;

    @BeforeEach
    void setUp() throws Exception {
        Config.init(configRoot);
        AE2Controller.serverPlatform = new TestPlatform(configRoot);
        AE2Controller.AE2Interface = new TestAE(42, false);
        CoreData.instance = new CoreData();
    }

    @Test
    void getPlayerIdUsesRegisteredPlayerUuidInsteadOfOfflineUuid() {
        CoreData.setPassword(REGISTERED_PLAYER, "hash");

        assertEquals(42, CoreData.getPlayerId("Player"));
    }

    @Test
    void setPasswordRejectsUnresolvedPlayerWithoutMutatingData() throws Exception {
        AE2Controller.AE2Interface = new TestAE(-1, false);

        assertFalse(CoreData.setPassword(REGISTERED_PLAYER, "hash"));

        assertEquals(-1, CoreData.getPlayerId("Player"));
        assertTrue(getMap("passwords").isEmpty());
        assertTrue(getMap("UUIDToId").isEmpty());
        assertTrue(getMap("IdToUUID").isEmpty());
    }

    @Test
    void setPasswordRejectsPlayerLookupExceptionWithoutMutatingData() throws Exception {
        AE2Controller.AE2Interface = new TestAE(42, true);

        assertFalse(CoreData.setPassword(REGISTERED_PLAYER, "hash"));

        assertEquals(-1, CoreData.getPlayerId("Player"));
        assertTrue(getMap("passwords").isEmpty());
        assertTrue(getMap("UUIDToId").isEmpty());
        assertTrue(getMap("IdToUUID").isEmpty());
    }

    private Map<?, ?> getMap(String fieldName) throws Exception {
        Field field = CoreData.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<?, ?>) field.get(CoreData.instance);
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

        private final int playerId;
        private final boolean throwOnPlayerLookup;

        TestAE(int playerId, boolean throwOnPlayerLookup) {
            this.playerId = playerId;
            this.throwOnPlayerLookup = throwOnPlayerLookup;
        }

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
                public int web$getPlayerId(PlayerIdentity identity) {
                    if (throwOnPlayerLookup) {
                        throw new IllegalStateException("player lookup failed");
                    }
                    return REGISTERED_UUID.equals(identity.uuid) ? playerId : -1;
                }
            };
        }
    }
}
