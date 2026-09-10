package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.function.Consumer;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.ICommandBuilder;
import pl.kuba6000.ae2webintegration.core.api.ICommandContext;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.commands.CommandBootstrap;
import pl.kuba6000.ae2webintegration.core.config.CoreDataTestFixture;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

/** Tests for {@link CommandBootstrap} command tree definition. */
@SuppressWarnings("PMD.AvoidMagicNumbers")
class CommandBootstrapTest {

    @BeforeEach
    void setUpPlayerLookup() {
        AE2Controller.AE2Interface = new TestAE();
        CoreDataTestFixture.reset();
    }

    @Test
    void testReloadHandler() {
        RecordingBuilder builder = new RecordingBuilder();
        CommandBootstrap.init(builder);

        // Create a context mock
        RecordingContext ctx = new RecordingContext();
        // Grant permission
        ctx.hasPermissionResult = true;
        ctx.reloader = () -> {};
        ctx.playerIdentity = new PlayerIdentity(UUID.randomUUID(), "Player");

        // Invoke the captured reload handler
        builder.reloadHandler.accept(ctx);

        // Verify: should have checked permission(4)
        assertTrue(ctx.lastPermissionCheck >= 4, "should check permission level >= 4");
        // Verify reload was triggered (reloader was run)
        assertTrue(ctx.reloaderRan, "reloader should have been invoked");
    }

    @Test
    void testReloadHandlerNoPermission() {
        RecordingBuilder builder = new RecordingBuilder();
        CommandBootstrap.init(builder);

        RecordingContext ctx = new RecordingContext();
        ctx.hasPermissionResult = false;

        builder.reloadHandler.accept(ctx);

        // Should send error when no permission
        assertNotNull(ctx.lastError, "should have sent error message");
        // Verify reload was NOT triggered
        assertFalse(ctx.reloaderRan, "reloader should NOT have been invoked");
    }

    @Test
    void testAuthHandlerWithToken() {
        RecordingBuilder builder = new RecordingBuilder();
        CommandBootstrap.init(builder);

        RecordingContext ctx = new RecordingContext();
        ctx.args = new String[] { "auth", "my-test-token" };
        ctx.playerIdentity = new PlayerIdentity(UUID.randomUUID(), "Player");

        // Put a registration in the awaiting map so registerPlayer succeeds
        UUID uuid = ctx.playerIdentity.uuid;
        AE2Controller.awaitingRegistration.put(uuid, Pair.of("my-test-token", "password-hash"));

        try {
            builder.authHandler.accept(ctx);

            // Should have read args[1] as token
            // RegisterPlayer should have succeeded
            assertNull(ctx.lastError, "no error expected");
        } finally {
            AE2Controller.awaitingRegistration.remove(uuid);
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

        @Override
        public IAEPlayerData web$getPlayerData() {
            return identity -> 42;
        }
    }

    // --- Recording ICommandContext stub ---

    private static class RecordingContext implements ICommandContext {

        String[] args = new String[0];
        int lastPermissionCheck = -1;
        boolean hasPermissionResult = false;
        String lastError;
        PlayerIdentity playerIdentity;
        Runnable reloader;
        boolean reloaderRan;

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

        @Override
        public Runnable getReloader() {
            return () -> {
                reloaderRan = true;
                if (reloader != null) reloader.run();
            };
        }
    }
}
