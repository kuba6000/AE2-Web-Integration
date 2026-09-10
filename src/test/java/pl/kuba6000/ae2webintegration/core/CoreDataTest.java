package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

@SuppressWarnings("PMD.AvoidMagicNumbers")
class CoreDataTest {

    private static final UUID REGISTERED_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final PlayerIdentity REGISTERED_PLAYER = new PlayerIdentity(REGISTERED_UUID, "Player");
    private static final UUID OTHER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final PlayerIdentity OTHER_PLAYER = new PlayerIdentity(OTHER_UUID, "OtherPlayer");
    @TempDir
    File configRoot;

    @BeforeEach
    void setUp() {
        Config.init(configRoot);
        AE2Controller.serverPlatform = new TestPlatform(configRoot);
        AE2Controller.AE2Interface = new TestAE(42, false);
        CoreDataTestFixture.reset();
    }

    @Test
    void accountUsesTheRegisteredPlayersStableIdentity() {
        CoreData.setPassword(REGISTERED_PLAYER, "hash");

        assertAccount("Player", REGISTERED_PLAYER);
    }

    @Test
    void setPasswordCreatesAnAccountWithoutConsultingAePlayerData() {
        AE2Controller.AE2Interface = new TestAE(42, true);

        assertTrue(CoreData.setPassword(REGISTERED_PLAYER, "hash"));
        assertNotNull(CoreData.getAccount("Player"));
    }

    // --- persistence: a bad file must never cost anyone their account ---

    @Test
    void verifyPasswordReturnsFalseForAKnownPlayerWithNoStoredPassword() {
        CoreData.setPassword(REGISTERED_PLAYER, "hash");
        CoreData.Account account = CoreData.getAccount("Player");
        CoreData.setPassword(REGISTERED_PLAYER, "");

        assertFalse(CoreData.verifyPassword(account, "anything"));
    }

    @Test
    void savedAccountsSurviveAReload() {
        CoreData.setPassword(REGISTERED_PLAYER, "storedhash");

        CoreDataTestFixture.reset();
        CoreData.loadData();

        assertAccount("Player", REGISTERED_PLAYER);
    }

    @Test
    void savedAccountIsResolvedByItsStoredNameWithoutAPlatformProfileLookup() {
        CoreData.setPassword(REGISTERED_PLAYER, "storedhash");

        CoreDataTestFixture.reset();
        CoreData.loadData();
        AE2Controller.serverPlatform = null;

        assertAccount("pLaYeR", REGISTERED_PLAYER);
    }

    @Test
    void anEmptyDataFileLeavesTheAccountsInMemoryAlone() throws Exception {
        CoreData.setPassword(REGISTERED_PLAYER, "storedhash");
        writeDataFile("");

        CoreData.loadData();

        assertAccount("Player", REGISTERED_PLAYER);
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

        assertAccount("Player", REGISTERED_PLAYER);
    }

    @Test
    void aSchemaTwoAccountWithAStoredNameRemainsUsableWithoutAnotherJoin() throws Exception {
        writeDataFile(
            "{\"schemaVersion\":2,\"UUIDToId\":{\"" + REGISTERED_UUID
                + "\":42},\"IdToUUID\":{\"42\":\""
                + REGISTERED_UUID
                + "\"},\"passwords\":{\""
                + REGISTERED_UUID
                + "\":\"legacyhash\"},\"usernames\":{\""
                + REGISTERED_UUID
                + "\":\"Player\"}}");

        CoreData.loadData();
        assertAccount("Player", REGISTERED_PLAYER);

        CoreDataTestFixture.reset();
        CoreData.loadData();
        assertAccount("Player", REGISTERED_PLAYER);
    }

    @Test
    void aLegacyPasswordAccountWithoutPersistedAeIdsIsCompletedWhenThePlayerIdentityIsObserved() throws Exception {
        writeDataFile("{\"passwords\":{\"" + REGISTERED_UUID + "\":\"legacyhash\"}}");

        CoreData.loadData();
        CoreEngine.onPlayerSeen(REGISTERED_PLAYER);

        assertNotNull(CoreData.getAccount("Player"));
    }

    @Test
    void aNameConfirmedForAnotherUuidStopsSelectingThePreviousAccount() {
        CoreData.setPassword(REGISTERED_PLAYER, "first-hash");
        CoreData.setPassword(OTHER_PLAYER, "second-hash");

        CoreEngine.onPlayerSeen(new PlayerIdentity(OTHER_UUID, "Player"));

        assertNull(CoreData.getAccount("OtherPlayer"));
        assertAccount("player", new PlayerIdentity(OTHER_UUID, "Player"));

        CoreDataTestFixture.reset();
        CoreData.loadData();

        assertNull(CoreData.getAccount("OtherPlayer"));
        assertAccount("player", new PlayerIdentity(OTHER_UUID, "Player"));
    }

    private static void assertAccount(String loginName, PlayerIdentity expectedIdentity) {
        CoreData.Account account = CoreData.getAccount(loginName);
        assertNotNull(account);
        assertEquals(expectedIdentity.uuid, account.getIdentity().uuid);
        assertEquals(expectedIdentity.name, account.getIdentity().name);
    }

    private File dataFile() {
        return new File(configRoot, "ae2webintegration/webdata.json");
    }

    @SuppressWarnings("ReadWriteStringCanBeUsed") // Files.writeString requires Java 11; tests also target Java 8.
    private void writeDataFile(String content) throws Exception {
        File file = dataFile();
        Files.createDirectories(
            file.getParentFile()
                .toPath());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("ReadWriteStringCanBeUsed") // Files.readString requires Java 11; tests also target Java 8.
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
            return identity -> {
                if (throwOnPlayerLookup) {
                    throw new IllegalStateException("player lookup failed");
                }
                if (REGISTERED_UUID.equals(identity.uuid)) {
                    return playerId;
                }
                return OTHER_UUID.equals(identity.uuid) ? 43 : -1;
            };
        }
    }
}
