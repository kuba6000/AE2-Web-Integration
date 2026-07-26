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

    // --- persistence: a bad file must never cost anyone their account ---

    @Test
    void verifyPasswordReturnsFalseForAKnownPlayerWithNoStoredPassword() throws Exception {
        CoreData.setPassword(REGISTERED_PLAYER, "hash");
        // Clearing a password removes only that entry; the id mappings stay.
        CoreData.setPassword(REGISTERED_PLAYER, "");

        assertFalse(CoreData.verifyPassword(42, "anything"));
    }

    @Test
    void savedAccountsSurviveAReload() {
        CoreData.setPassword(REGISTERED_PLAYER, "storedhash");

        CoreData.instance = new CoreData();
        CoreData.loadData();

        assertEquals(42, CoreData.getPlayerId("Player"));
    }

    @Test
    void anEmptyDataFileLeavesTheAccountsInMemoryAlone() throws Exception {
        CoreData.setPassword(REGISTERED_PLAYER, "storedhash");
        writeDataFile("");

        CoreData.loadData();

        assertEquals(42, CoreData.getPlayerId("Player"), "a broken file must not wipe live accounts");
    }

    @Test
    void aMalformedDataFileIsNotOverwritten() throws Exception {
        CoreData.setPassword(REGISTERED_PLAYER, "storedhash");
        String garbage = "{ this is not json";
        writeDataFile(garbage);

        CoreData.loadData();

        assertEquals(garbage, readDataFile(), "a failed read must not persist over the file it failed on");
    }

    @Test
    void saveLeavesNoTemporaryFileBehind() {
        CoreData.setPassword(REGISTERED_PLAYER, "storedhash");

        assertFalse(new File(configRoot, "ae2webintegration/webdata.json.tmp").exists());
    }

    @Test
    void aFileWrittenBeforeVersioningStillLoads() throws Exception {
        // Same shape as the old format: no schemaVersion field at all.
        writeDataFile(
            "{\"UUIDToId\":{\"" + REGISTERED_UUID
                + "\":42},\"IdToUUID\":{\"42\":\""
                + REGISTERED_UUID
                + "\"},\"passwords\":{\""
                + REGISTERED_UUID
                + "\":\"legacyhash\"}}");

        CoreData.loadData();

        assertEquals(42, CoreData.getPlayerId("Player"));
    }

    private File dataFile() {
        return new File(configRoot, "ae2webintegration/webdata.json");
    }

    private void writeDataFile(String content) throws Exception {
        File file = dataFile();
        file.getParentFile()
            .mkdirs();
        java.nio.file.Files.write(file.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String readDataFile() throws Exception {
        return new String(
            java.nio.file.Files.readAllBytes(dataFile().toPath()),
            java.nio.charset.StandardCharsets.UTF_8);
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
