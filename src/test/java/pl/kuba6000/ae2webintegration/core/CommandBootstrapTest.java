package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.ICommandBuilder;
import pl.kuba6000.ae2webintegration.core.api.ICommandContext;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.commands.CommandBootstrap;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigTestFixture;
import pl.kuba6000.ae2webintegration.core.config.CoreDataTestFixture;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

/** Tests for {@link CommandBootstrap} command tree definition. */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class CommandBootstrapTest {

    private ConfigTestFixture config;

    @BeforeEach
    void setUpPlayerLookup() {
        config = new ConfigTestFixture();
        config.set("general.port", ConfigTestFixture.unusedLoopbackPort());
        AE2Controller.AE2Interface = new TestAE();
        CoreDataTestFixture.reset();
    }

    @AfterEach
    void tearDown() {
        AE2Controller.stopHTTPServer();
        config.close();
    }

    @Test
    void testReloadHandler() {
        RecordingBuilder builder = new RecordingBuilder();
        CommandBootstrap.init(builder);

        // Create a context mock
        RecordingContext ctx = new RecordingContext();
        // Grant permission
        ctx.hasPermissionResult = true;
        config.write("general.public_mode", false);
        ctx.playerIdentity = new PlayerIdentity(UUID.randomUUID(), "Player");

        // Invoke the captured reload handler
        builder.reloadHandler.accept(ctx);

        // Verify: should have checked permission(4)
        assertTrue(ctx.lastPermissionCheck >= 4, "should check permission level >= 4");
        // Reload applies the edited file through the command.
        assertFalse(Config.AE_PUBLIC_MODE());
        assertNull(ctx.lastError);
    }

    @Test
    void testReloadHandlerNoPermission() {
        RecordingBuilder builder = new RecordingBuilder();
        CommandBootstrap.init(builder);

        RecordingContext ctx = new RecordingContext();
        ctx.hasPermissionResult = false;
        config.write("general.public_mode", false);

        builder.reloadHandler.accept(ctx);

        // Should send error when no permission
        assertNotNull(ctx.lastError, "should have sent error message");
        // The denied command leaves the active settings unchanged.
        assertTrue(Config.AE_PUBLIC_MODE());
    }

    @Test
    void testAuthHandlerWithToken() throws Exception {
        RecordingBuilder builder = new RecordingBuilder();
        CommandBootstrap.init(builder);

        RecordingContext ctx = new RecordingContext();
        ctx.args = new String[] { "auth", "my-test-token" };
        ctx.playerIdentity = new PlayerIdentity(UUID.randomUUID(), "Player");

        try (RegistrationTestFixture registrations = new RegistrationTestFixture()) {
            String token = registrations.begin(ctx.playerIdentity, "test-password");
            ctx.args = new String[] { "auth", token };
            builder.authHandler.accept(ctx);
            assertNull(ctx.lastError, "no error expected");
            builder.authHandler.accept(ctx);
            assertNotNull(ctx.lastError, "confirmation tokens are consumed by the command");
        }
    }

    @Test
    void testAuthHandlerWithoutArgs() {
        RecordingBuilder builder = new RecordingBuilder();
        CommandBootstrap.init(builder);

        RecordingContext ctx = new RecordingContext();
        ctx.args = new String[0];
        ctx.playerIdentity = new PlayerIdentity(UUID.randomUUID(), "Player");

        builder.authHandler.accept(ctx);

        // Should show usage when no token arg provided
        assertNotNull(ctx.lastError, "should have sent error for missing args");
    }

    @Test
    void testAuthHandlerConsoleSender() {
        RecordingBuilder builder = new RecordingBuilder();
        CommandBootstrap.init(builder);

        RecordingContext ctx = new RecordingContext();
        ctx.args = new String[] { "auth", "token" };
        ctx.playerIdentity = null; // Console = no player identity

        builder.authHandler.accept(ctx);

        // Should reject non-player usage
        assertNotNull(ctx.lastError, "should have sent error for console sender");
    }

    // --- Recording ICommandBuilder stub ---

    private static class RecordingBuilder implements ICommandBuilder {

        final RecordingBuilder root;
        final RecordingBuilder parent;
        Consumer<ICommandContext> reloadHandler;
        Consumer<ICommandContext> authHandler;

        /** Root constructor. */
        RecordingBuilder() {
            this.root = this;
            this.parent = null;
        }

        /** Child constructor — inherits the root's call list. */
        RecordingBuilder(RecordingBuilder parent) {
            this.root = parent.root;
            this.parent = parent;
        }

        @Override
        public ICommandBuilder literal(String name, int permission) {
            return new RecordingBuilder(this);
        }

        @Override
        public ICommandBuilder argument(String name) {
            return new RecordingBuilder(this);
        }

        @Override
        public ICommandBuilder executes(Consumer<ICommandContext> handler) {
            if (root.reloadHandler == null) {
                root.reloadHandler = handler;
            } else {
                root.authHandler = handler;
            }
            return parent != null ? parent : this;
        }

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

    // --- Recording ICommandContext stub ---

    private static class RecordingContext implements ICommandContext {

        String[] args = new String[0];
        int lastPermissionCheck = -1;
        boolean hasPermissionResult = false;
        String lastError;
        PlayerIdentity playerIdentity;

        @Override
        public String[] getArgs() {
            return args;
        }

        @Override
        public PlayerIdentity getPlayerIdentity() {
            return playerIdentity;
        }

        @Override
        public boolean hasPermission(int level) {
            lastPermissionCheck = level;
            return hasPermissionResult;
        }

        @Override
        public void sendMessage(String text) {}

        @Override
        public void sendError(String text) {
            lastError = text;
        }

    }
}
