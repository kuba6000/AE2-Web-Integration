package pl.kuba6000.ae2webintegration.core;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.ILegacyConfigProvider;
import pl.kuba6000.ae2webintegration.core.api.IServerPlatform;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.config.CoreDataTestFixture;
import pl.kuba6000.ae2webintegration.core.utils.ReleaseManifest;

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
        AE2Controller.AE2Interface = null;
        CoreDataTestFixture.reset();
    }

    @Test
    void accountUsesTheRegisteredPlayersStableIdentity() {
        CoreData.setPassword(REGISTERED_PLAYER, "hash");

        assertAccount("Player", REGISTERED_PLAYER);
    }

    @Test
    void setPasswordCreatesAnAccountWithoutAeState() {
        AE2Controller.AE2Interface = null;

        assertTrue(CoreData.setPassword(REGISTERED_PLAYER, "hash"));
        assertNotNull(CoreData.getAccount("Player"));
    }

    // --- persistence: a bad file must never cost anyone their account ---

    @Test
    void passwordReplacementInvalidatesAnAccountReadWithThePreviousCredential() throws Exception {
        assertTrue(
            CoreData.setPassword(REGISTERED_PLAYER, PasswordHelper.generateStrongPasswordHash("first-password")));
        CoreData.Account account = CoreData.getAccount("Player");
        assertNotNull(account);
        assertTrue(CoreData.verifyPassword(account, "first-password"));

        assertTrue(
            CoreData.setPassword(REGISTERED_PLAYER, PasswordHelper.generateStrongPasswordHash("second-password")));

        assertFalse(CoreData.verifyPassword(account, "first-password"));
        assertTrue(CoreData.verifyPassword(CoreData.getAccount("Player"), "second-password"));
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

    @ParameterizedTest
    @ValueSource(strings = { "{ this is not json", "", "null" })
    void aFailedInitialLoadDoesNotAllowLaterPasswordChangesToReplaceTheSourceFile(String unreadableAccounts)
        throws Exception {
        writeDataFile(unreadableAccounts);
        CoreData.loadData();

        boolean changed = CoreData.setPassword(REGISTERED_PLAYER, "newhash");

        assertEquals(unreadableAccounts, readDataFile());
        assertFalse(changed);
        assertNull(CoreData.getAccount("Player"));
    }

    @Test
    void aFailedLoadDoesNotAllowAnUpdatePreferenceToReplaceTheSourceFile() throws Exception {
        String unreadableAccounts = "{ this is not json";
        writeDataFile(unreadableAccounts);
        CoreData.loadData();
        ReleaseManifest.Release release = ReleaseManifest
            .parse(
                """
                    {"version":"1.20.1","releases":{"stable":{
                      "newest":"2.1.0","timestamp":1700000000,"github_release_tag":"2.1.0-forge-1.20.1",
                      "github_release_url":"https://github.com/example/repo/releases/tag/2.1.0-forge-1.20.1",
                      "github_release_download_url":"https://github.com/example/repo/releases/download/2.1.0-forge-1.20.1/mod.jar"
                    },"prerelease":null}}
                    """,
                "1.20.1")
            .findUpdate("2.0.0-forge-1.20.1", "forge");
        assertNotNull(release);

        assertThrows(IOException.class, () -> CoreData.skipUpdate("1.20.1", "forge", release));

        assertEquals(unreadableAccounts, readDataFile());
        assertFalse(CoreData.isUpdateSkipped("1.20.1", "forge", release));
    }

    @Test
    void failedLoadStaysQuarantinedUntilAnAccountFileLoadsSuccessfully() throws Exception {
        CoreData.setPassword(REGISTERED_PLAYER, "storedhash");
        String savedAccounts = readDataFile();
        writeDataFile("{ this is not json");
        CoreData.loadData();
        Files.delete(dataFile().toPath());

        CoreData.loadData();

        assertFalse(dataFile().exists());
        assertFalse(CoreData.setPassword(OTHER_PLAYER, "otherhash"));
        assertAccount("Player", REGISTERED_PLAYER);
        assertNull(CoreData.getAccount("OtherPlayer"));

        writeDataFile(savedAccounts);
        CoreData.loadData();
        assertTrue(CoreData.setPassword(OTHER_PLAYER, "otherhash"));
        CoreDataTestFixture.reset();
        CoreData.loadData();
        assertAccount("Player", REGISTERED_PLAYER);
        assertAccount("OtherPlayer", OTHER_PLAYER);
    }

    @Test
    void aFailedReloadDoesNotAllowPlayerRenamesToReplaceTheSourceFile() throws Exception {
        CoreData.setPassword(REGISTERED_PLAYER, "storedhash");
        String unreadableAccounts = "{ this is not json";
        writeDataFile(unreadableAccounts);
        CoreData.loadData();

        CoreData.observePlayer(new PlayerIdentity(REGISTERED_UUID, "RenamedPlayer"));

        assertEquals(unreadableAccounts, readDataFile());
        assertAccount("Player", REGISTERED_PLAYER);
        assertNull(CoreData.getAccount("RenamedPlayer"));
        assertFalse(CoreData.setPassword(REGISTERED_PLAYER, "replacementhash"));
        assertEquals("storedhash", CoreData.getCredentialVersion(REGISTERED_UUID));
    }

    @Test
    void aFailedPasswordSavePreservesAccountsAndNameOwnershipUntilRetry() throws Exception {
        CoreData.setPassword(REGISTERED_PLAYER, "storedhash");
        CoreData.setPassword(OTHER_PLAYER, "otherhash");
        String savedAccounts = readDataFile();
        PlayerIdentity renamed = new PlayerIdentity(REGISTERED_UUID, "OtherPlayer");
        Path blocker = new File(dataFile() + ".tmp").toPath();
        Files.createDirectory(blocker);
        Files.write(blocker.resolve("keep-directory-nonempty"), new byte[] { 1 });
        try {
            assertFalse(CoreData.setPassword(renamed, "newhash"));
            assertEquals(savedAccounts, readDataFile());
            assertAccount("Player", REGISTERED_PLAYER);
            assertAccount("OtherPlayer", OTHER_PLAYER);
            assertEquals("storedhash", CoreData.getCredentialVersion(REGISTERED_UUID));
            assertEquals("otherhash", CoreData.getCredentialVersion(OTHER_UUID));
        } finally {
            Files.delete(blocker.resolve("keep-directory-nonempty"));
            Files.delete(blocker);
        }

        assertTrue(CoreData.setPassword(renamed, "newhash"));
        CoreDataTestFixture.reset();
        CoreData.loadData();
        assertNull(CoreData.getAccount("Player"));
        assertAccount("OtherPlayer", renamed);
        assertEquals("newhash", CoreData.getCredentialVersion(REGISTERED_UUID));
        assertEquals("otherhash", CoreData.getCredentialVersion(OTHER_UUID));
    }

    @Test
    void saveLeavesNoTemporaryFileBehind() {
        CoreData.setPassword(REGISTERED_PLAYER, "storedhash");

        assertFalse(new File(configRoot, "ae2webintegration/webdata.json.tmp").exists());
    }

    @Test
    void loadingAccountsDoesNotRewriteTheStoredDocument() throws Exception {
        String document = "{\"passwords\":{\"" + REGISTERED_UUID
            + "\":\"storedhash\"},\"usernames\":{\""
            + REGISTERED_UUID
            + "\":\"Player\"}}";
        writeDataFile(document);

        CoreData.loadData();

        assertAccount("Player", REGISTERED_PLAYER);
        assertEquals(document, readDataFile());
    }

    @Test
    void observingAPlayerRenameUpdatesThePersistedLoginName() {
        CoreData.setPassword(REGISTERED_PLAYER, "storedhash");
        PlayerIdentity renamed = new PlayerIdentity(REGISTERED_UUID, "RenamedPlayer");

        CoreEngine.onPlayerSeen(renamed);
        CoreDataTestFixture.reset();
        CoreData.loadData();

        assertNull(CoreData.getAccount("Player"));
        assertAccount("renamedplayer", renamed);
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
        Files.write(file.toPath(), content.getBytes(UTF_8));
    }

    @SuppressWarnings("ReadWriteStringCanBeUsed") // Files.readString requires Java 11; tests also target Java 8.
    private String readDataFile() throws Exception {
        return new String(Files.readAllBytes(dataFile().toPath()), UTF_8);
    }

    @Desugar
    private record TestPlatform(File configDirectory) implements IServerPlatform {

        @Override
        public Path getModFile() {
            return null;
        }

        @Override
        public boolean isSingleplayer() {
            return false;
        }

        @Override
        public @NotNull String getModVersion() {
            return "test-version";
        }

        @Override
        public @NotNull String getLoader() {
            return "forge";
        }

        @Override
        public @NotNull String getMinecraftVersion() {
            return "1.20.1";
        }

        @Override
        public @NotNull String getIconPackCompatibilityVersion() {
            return "test-compatibility";
        }

        @Override
        public UUID getOnlinePlayerUUID(String username) {
            return REGISTERED_UUID;
        }

        @Override
        public ILegacyConfigProvider getLegacyConfig() {
            return null;
        }

        @Override
        public File getConfigDirectory() {
            return configDirectory;
        }

        @Override
        public File getWorldDirectory() {
            return new File(configDirectory, "test-save");
        }
    }

}
