package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.CommandResult;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.commands.CommandProcessor;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.CoreData;
import pl.kuba6000.ae2webintegration.core.config.CoreDataTestFixture;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

/** Tests for {@link CommandProcessor} static methods. */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class CommandProcessorTest {

    private static final UUID TEST_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID OTHER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final PlayerIdentity TEST_PLAYER = new PlayerIdentity(TEST_UUID, "Player");
    private static final PlayerIdentity OTHER_PLAYER = new PlayerIdentity(OTHER_UUID, "OtherPlayer");

    private static File previousConfigDirectory;
    private RegistrationTestFixture registrations;

    @BeforeAll
    static void setupConfig() {
        previousConfigDirectory = Config.getConfigDirectory();
        Config.init(new File(System.getProperty("java.io.tmpdir")));
    }

    @AfterAll
    static void restoreConfig() {
        if (previousConfigDirectory != null) {
            Config.init(previousConfigDirectory.getParentFile());
        }
    }

    @BeforeEach
    void setUp() {
        registrations = new RegistrationTestFixture();
        AE2Controller.AE2Interface = new TestAE();
        CoreDataTestFixture.reset();
    }

    @AfterEach
    void tearDown() {
        registrations.close();
    }

    // --- reload tests ---

    @Test
    void testReloadSuccess() {
        CommandResult result = CommandProcessor.reload(() -> {});
        assertTrue(result.isSuccess(), "reload should succeed");
        assertNotNull(result.getMessage());
        assertTrue(
            result.getMessage()
                .toLowerCase()
                .contains("success"),
            "message should indicate success: " + result.getMessage());
    }

    @Test
    void testReloadFailure() {
        CommandResult result = CommandProcessor.reload(() -> { throw new RuntimeException("simulated failure"); });
        assertFalse(result.isSuccess(), "reload should fail when configReloader throws");
        assertTrue(
            result.getMessage()
                .toLowerCase()
                .contains("fail"),
            "message should indicate failure: " + result.getMessage());
    }

    @Test
    void repeatedReloadsCreateUsableServerLifecycles() {
        assertTrue(
            CommandProcessor.reload(() -> {})
                .isSuccess());
        assertTrue(
            CommandProcessor.reload(() -> {})
                .isSuccess());
    }

    // --- registerPlayer tests ---

    @Test
    void testRegisterPlayerWithValidToken() throws Exception {
        String token = registrations.begin(TEST_PLAYER, "test-password");
        assertTrue(
            CommandProcessor.registerPlayer(TEST_PLAYER, token)
                .isSuccess());
        assertTrue(CoreData.verifyPassword(CoreData.getAccount(TEST_PLAYER.name), "test-password"));
        assertFalse(
            CommandProcessor.registerPlayer(TEST_PLAYER, token)
                .isSuccess(),
            "confirmation tokens are single-use");
    }

    @Test
    void testRegisterPlayerWithInvalidToken() throws Exception {
        String token = registrations.begin(TEST_PLAYER, "test-password");
        assertFalse(
            CommandProcessor.registerPlayer(TEST_PLAYER, "wrong-token")
                .isSuccess());
        assertNull(CoreData.getAccount(TEST_PLAYER.name));
        assertTrue(
            CommandProcessor.registerPlayer(TEST_PLAYER, token)
                .isSuccess(),
            "a wrong token does not consume registration");
    }

    @Test
    void testRegisterPlayerDoesNotRequireAeState() throws Exception {
        String token = registrations.begin(TEST_PLAYER, "test-password");
        AE2Controller.AE2Interface = null;
        assertTrue(
            CommandProcessor.registerPlayer(TEST_PLAYER, token)
                .isSuccess());
    }

    @Test
    void testRegisterPlayerNoRegistration() {
        assertFalse(
            CommandProcessor.registerPlayer(TEST_PLAYER, "any-token")
                .isSuccess());
    }

    @Test
    void testRegisterPlayerMultipleRegistrations() throws Exception {
        String token1 = registrations.begin(TEST_PLAYER, "first-password");
        String token2 = registrations.begin(OTHER_PLAYER, "second-password");
        assertFalse(
            CommandProcessor.registerPlayer(OTHER_PLAYER, token1)
                .isSuccess());
        assertTrue(
            CommandProcessor.registerPlayer(TEST_PLAYER, token1)
                .isSuccess());
        assertTrue(
            CommandProcessor.registerPlayer(OTHER_PLAYER, token2)
                .isSuccess());
        assertTrue(CoreData.verifyPassword(CoreData.getAccount(TEST_PLAYER.name), "first-password"));
        assertTrue(CoreData.verifyPassword(CoreData.getAccount(OTHER_PLAYER.name), "second-password"));
    }

    private static class TestAE implements IAE {

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

    }
}
