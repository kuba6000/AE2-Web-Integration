package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.ServerSocket;
import java.util.UUID;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.CommandResult;
import pl.kuba6000.ae2webintegration.core.api.IConfigValue;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.commands.CommandProcessor;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigBootstrap;
import pl.kuba6000.ae2webintegration.core.config.CoreDataTestFixture;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

/** Tests for {@link CommandProcessor} static methods. */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class CommandProcessorTest {

    private static final UUID TEST_UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID OTHER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final PlayerIdentity TEST_PLAYER = new PlayerIdentity(TEST_UUID, "Player");
    private static final PlayerIdentity OTHER_PLAYER = new PlayerIdentity(OTHER_UUID, "OtherPlayer");
    private static IConfigValue<Integer> previousPort;
    private static File previousConfigDirectory;

    @BeforeAll
    static void setupServer() throws Exception {
        previousPort = ConfigBootstrap.aePortValue;
        previousConfigDirectory = Config.getConfigDirectory();
        int testPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            testPort = socket.getLocalPort();
        }
        ConfigBootstrap.aePortValue = () -> testPort;
        // Initialize Config so CoreData can resolve its data file path
        Config.init(new File(System.getProperty("java.io.tmpdir")));

        // Own a complete listener lifecycle even when another static-state test ran first in this JVM.
        AE2Controller.stopHTTPServer();
        AE2Controller.startHTTPServer();
    }

    @AfterAll
    static void stopServer() {
        AE2Controller.stopHTTPServer();
        ConfigBootstrap.aePortValue = previousPort;
        if (previousConfigDirectory != null) {
            Config.init(previousConfigDirectory.getParentFile());
        }
    }

    @BeforeEach
    void setUp() {
        // Clean registration map before each test
        AE2Controller.awaitingRegistration.clear();
        AE2Controller.AE2Interface = new TestAE(42);
        CoreDataTestFixture.reset();
    }

    @AfterEach
    void tearDown() {
        AE2Controller.awaitingRegistration.clear();
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
    void testRegisterPlayerWithValidToken() {
        String passwordHash = "test-password-hash";
        String token = "correct-token";
        AE2Controller.awaitingRegistration.put(TEST_UUID, Pair.of(token, passwordHash));

        CommandResult result = CommandProcessor.registerPlayer(TEST_PLAYER, token);

        assertTrue(result.isSuccess(), "registration should succeed with valid token");
        assertFalse(
            AE2Controller.awaitingRegistration.containsKey(TEST_UUID),
            "registration should be removed after successful auth");
    }

    @Test
    void testRegisterPlayerWithInvalidToken() {
        String passwordHash = "test-password-hash";
        AE2Controller.awaitingRegistration.put(TEST_UUID, Pair.of("correct-token", passwordHash));

        CommandResult result = CommandProcessor.registerPlayer(TEST_PLAYER, "wrong-token");

        assertFalse(result.isSuccess(), "registration should fail with wrong token");
        assertEquals("Invalid token!", result.getMessage());
        // Registration should NOT be removed on failure
        assertTrue(
            AE2Controller.awaitingRegistration.containsKey(TEST_UUID),
            "registration should persist after failed auth");
    }

    @Test
    void testRegisterPlayerDoesNotRequireAWorldScopedAePlayerId() {
        String token = "correct-token";
        AE2Controller.AE2Interface = new TestAE(-1);
        AE2Controller.awaitingRegistration.put(TEST_UUID, Pair.of(token, "hash"));

        CommandResult result = CommandProcessor.registerPlayer(TEST_PLAYER, token);

        assertTrue(result.isSuccess());
        assertFalse(AE2Controller.awaitingRegistration.containsKey(TEST_UUID));
    }

    @Test
    void testRegisterPlayerNoRegistration() {
        CommandResult result = CommandProcessor.registerPlayer(TEST_PLAYER, "any-token");
        assertFalse(result.isSuccess(), "registration should fail when no registration exists");
        assertTrue(
            result.getMessage()
                .toLowerCase()
                .contains("initialize"),
            "error should mention initialization: " + result.getMessage());
    }

    @Test
    void testRegisterPlayerMultipleRegistrations() {
        // Two different players with different tokens
        String token1 = "token1";
        String token2 = "token2";
        AE2Controller.awaitingRegistration.put(TEST_UUID, Pair.of(token1, "hash1"));
        AE2Controller.awaitingRegistration.put(OTHER_UUID, Pair.of(token2, "hash2"));

        // Auth the first player
        CommandResult result1 = CommandProcessor.registerPlayer(TEST_PLAYER, token1);
        assertTrue(result1.isSuccess(), "player 1 should succeed");
        assertFalse(
            AE2Controller.awaitingRegistration.containsKey(TEST_UUID),
            "player 1 registration should be removed");

        // Auth the second player
        CommandResult result2 = CommandProcessor.registerPlayer(OTHER_PLAYER, token2);
        assertTrue(result2.isSuccess(), "player 2 should succeed");
        assertFalse(
            AE2Controller.awaitingRegistration.containsKey(OTHER_UUID),
            "player 2 registration should be removed");
    }

    private static class TestAE implements IAE {

        private final int playerId;

        TestAE(int playerId) {
            this.playerId = playerId;
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
            return identity -> playerId;
        }
    }
}
