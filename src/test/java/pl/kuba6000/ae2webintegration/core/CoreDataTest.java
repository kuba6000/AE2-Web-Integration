package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.config.CoreDataTestFixture;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

class CoreDataTest {

    private static final UUID REGISTERED_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final PlayerIdentity REGISTERED_PLAYER = new PlayerIdentity(REGISTERED_UUID, "Player");
    private static final UUID OTHER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final PlayerIdentity OTHER_PLAYER = new PlayerIdentity(OTHER_UUID, "OtherPlayer");
    @TempDir
    File configRoot;

    @BeforeEach
    void setUp() throws Exception {
        Config.init(configRoot);
        AE2Controller.serverPlatform = new TestPlatform(configRoot);
        AE2Controller.AE2Interface = new TestAE(42, false);
        CoreDataTestFixture.reset();
    }

    @Test
    void getPlayerIdUsesRegisteredPlayerUuidInsteadOfOfflineUuid() {
        CoreData.setPassword(REGISTERED_PLAYER, "hash");

        assertEquals(42, CoreData.getPlayerId("Player"));
    }

    @Test
    void setPasswordRejectsUnresolvedPlayerWithoutCreatingAnAccount() {
        AE2Controller.AE2Interface = new TestAE(-1, false);

        assertFalse(CoreData.setPassword(REGISTERED_PLAYER, "hash"));

        assertEquals(-1, CoreData.getPlayerId("Player"));
        assertNull(CoreData.getPlayerName(42));
    }

    @Test
    void setPasswordRejectsPlayerLookupExceptionWithoutCreatingAnAccount() {
        AE2Controller.AE2Interface = new TestAE(42, true);

        assertFalse(CoreData.setPassword(REGISTERED_PLAYER, "hash"));

        assertEquals(-1, CoreData.getPlayerId("Player"));
        assertNull(CoreData.getPlayerName(42));
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

        CoreDataTestFixture.reset();
        CoreData.loadData();

        assertEquals(42, CoreData.getPlayerId("Player"));
    }

    @Test
    void savedAccountIsResolvedByItsStoredNameWithoutAPlatformProfileLookup() {
        CoreData.setPassword(REGISTERED_PLAYER, "storedhash");

        CoreDataTestFixture.reset();
        CoreData.loadData();
        AE2Controller.serverPlatform = null;

        assertEquals(42, CoreData.getPlayerId("pLaYeR"));
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
    void aFileWrittenBeforeVersioningIsCompletedWhenThePlayerIdentityIsObserved() throws Exception {
        // Same shape as the old format: no schemaVersion field at all.
        writeDataFile(
            "{\"UUIDToId\":{\"" + REGISTERED_UUID
                + "\":42},\"IdToUUID\":{\"42\":\""
                + REGISTERED_UUID
                + "\"},\"passwords\":{\""
                + REGISTERED_UUID
                + "\":\"legacyhash\"}}");

        CoreData.loadData();
        CoreEngine.onPlayerSeen(REGISTERED_PLAYER);

        CoreDataTestFixture.reset();
        CoreData.loadData();

        assertEquals(42, CoreData.getPlayerId("Player"));
    }

    @Test
    void aNameConfirmedForAnotherUuidStopsSelectingThePreviousAccount() {
        CoreData.setPassword(REGISTERED_PLAYER, "first-hash");
        CoreData.setPassword(OTHER_PLAYER, "second-hash");

        CoreEngine.onPlayerSeen(new PlayerIdentity(OTHER_UUID, "Player"));

        assertNull(CoreData.getPlayerName(42));
        assertEquals("Player", CoreData.getPlayerName(43));
        assertEquals(43, CoreData.getPlayerId("player"));

        CoreDataTestFixture.reset();
        CoreData.loadData();

        assertNull(CoreData.getPlayerName(42));
        assertEquals("Player", CoreData.getPlayerName(43));
        assertEquals(43, CoreData.getPlayerId("player"));
    }

    private File dataFile() {
        return new File(configRoot, "ae2webintegration/webdata.json");
    }

    private void writeDataFile(String content) throws Exception {
        File file = dataFile();
        file.getParentFile()
            .mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private String readDataFile() throws Exception {
        return new String(Files.readAllBytes(dataFile().toPath()), StandardCharsets.UTF_8);
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
        public Iterable<IAEGrid> web$getGrids() {
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
                public PlayerIdentity web$getPlayerProfile(int playerId) {
                    return null;
                }

                @Override
                public int web$getPlayerId(PlayerIdentity identity) {
                    if (throwOnPlayerLookup) {
                        throw new IllegalStateException("player lookup failed");
                    }
                    if (REGISTERED_UUID.equals(identity.uuid)) {
                        return playerId;
                    }
                    return OTHER_UUID.equals(identity.uuid) ? 43 : -1;
                }
            };
        }
    }
}
