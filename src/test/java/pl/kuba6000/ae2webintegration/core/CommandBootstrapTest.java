package pl.kuba6000.ae2webintegration.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pl.kuba6000.ae2webintegration.core.api.ICommandBuilder;
import pl.kuba6000.ae2webintegration.core.api.ICommandContext;
import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;
import pl.kuba6000.ae2webintegration.core.interfaces.IAE;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEPlayerData;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;

/** Tests for {@link CommandBootstrap} command tree definition. */
class CommandBootstrapTest {

    @BeforeEach
    void setUpPlayerLookup() {
        AE2Controller.AE2Interface = new TestAE();
        CoreData.instance = new CoreData();
    }

    @Test
    void testCommandTreeStructure() {
        RecordingBuilder builder = new RecordingBuilder();
        CommandBootstrap.init(builder);

        // Expected fluent call sequence:
        // literal("ae2webintegration", 0)
        // .literal("reload", 4)
        // .executes(handler)
        // .literal("auth", 0)
        // .argument("token")
        // .executes(handler)
        // builder.register()

        assertEquals(7, builder.calls.size(), "Expected 7 fluent calls + register");

        // Level 0: ae2webintegration root
        assertEquals("literal:ae2webintegration:0", builder.calls.get(0), "call[0]");

        // Level 1: reload child
        assertEquals("literal:reload:4", builder.calls.get(1), "call[1]");

        // Level 2: executes on reload (sets handler)
        assertEquals("executes", builder.calls.get(2), "call[2]");

        // Level 1: auth sibling
        assertEquals("literal:auth:0", builder.calls.get(3), "call[3]");

        // Level 2: token argument child
        assertEquals("argument:token", builder.calls.get(4), "call[4]");

        // Level 3: executes on token
        assertEquals("executes", builder.calls.get(5), "call[5]");

        // Finalize
        assertEquals("register", builder.calls.get(6), "call[6]");

        // Verify handler was captured
        assertNotNull(builder.reloadHandler, "reload handler should be stored");
        assertNotNull(builder.authHandler, "auth handler should be stored");
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
        assertTrue(
            ctx.lastError.toLowerCase()
                .contains("permission"),
            "error should mention permission: " + ctx.lastError);
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
        AE2Controller.awaitingRegistration
            .put(uuid, org.apache.commons.lang3.tuple.Pair.of("my-test-token", "password-hash"));

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
        assertTrue(ctx.lastError.contains("auth"), "error should mention auth subcommand");
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
        assertTrue(
            ctx.lastError.toLowerCase()
                .contains("player"),
            "error should mention player-only restriction");
    }

    // --- Recording ICommandBuilder stub ---

    private static class RecordingBuilder implements ICommandBuilder {

        final List<String> calls;
        final RecordingBuilder root;
        final RecordingBuilder parent;
        Consumer<ICommandContext> reloadHandler;
        Consumer<ICommandContext> authHandler;

        /** Root constructor. */
        RecordingBuilder() {
            this.calls = new ArrayList<>();
            this.root = this;
            this.parent = null;
        }

        /** Child constructor — inherits the root's call list. */
        RecordingBuilder(RecordingBuilder parent) {
            this.calls = parent.calls;
            this.root = parent.root;
            this.parent = parent;
        }

        @Override
        public ICommandBuilder literal(String name, int permission) {
            calls.add("literal:" + name + ":" + permission);
            return new RecordingBuilder(this);
        }

        @Override
        public ICommandBuilder argument(String name) {
            calls.add("argument:" + name);
            return new RecordingBuilder(this);
        }

        @Override
        public ICommandBuilder executes(Consumer<ICommandContext> handler) {
            calls.add("executes");
            if (root.reloadHandler == null) {
                root.reloadHandler = handler;
            } else {
                root.authHandler = handler;
            }
            return parent != null ? parent : this;
        }

        @Override
        public void register() {
            calls.add("register");
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
                public int web$getPlayerId(PlayerIdentity identity) {
                    return 42;
                }
            };
        }
    }

    // --- Recording ICommandContext stub ---

    private static class RecordingContext implements ICommandContext {

        String[] args = new String[0];
        int lastPermissionCheck = -1;
        boolean hasPermissionResult = false;
        String lastMessage;
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
        public void sendMessage(String text) {
            lastMessage = text;
        }

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
