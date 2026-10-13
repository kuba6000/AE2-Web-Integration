package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.context.CommandContext;

import pl.kuba6000.ae2webintegration.icongenerator.IconGeneratorMod;

@EventBusSubscriber(modid = IconGeneratorMod.MOD_ID, value = Dist.CLIENT)
public final class ClientBootstrap {

    private static final long PROGRESS_MESSAGE_INTERVAL = TimeUnit.SECONDS.toNanos(5);
    private static final AtomicLong RESOURCE_GENERATION = new AtomicLong();
    private static @Nullable ExportSession session;
    private static @Nullable ExportProgressScreen progressScreen;
    private static long sessionResourceGeneration;
    private static long lastProgressMessage;
    private static String lastStatus = "No icon export started";

    private ClientBootstrap() {}

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent event) {
        event.getDispatcher()
            .register(
                Commands.literal("ae2webicons")
                    .then(
                        Commands.literal("export")
                            .executes(context -> respond(context, start(true)))
                            .then(
                                Commands.literal("nopbo")
                                    .executes(context -> respond(context, start(false)))))
                    .then(
                        Commands.literal("status")
                            .executes(context -> respond(context, status())))
                    .then(
                        Commands.literal("cancel")
                            .executes(context -> {
                                cancel();
                                return respond(context, status());
                            })));
    }

    private static int respond(CommandContext<CommandSourceStack> context, String response) {
        context.getSource()
            .sendSuccess(() -> Component.literal(response), false);
        return 1;
    }

    private static String status() {
        return session == null ? lastStatus : session.status();
    }

    private static String start(boolean allowPbo) {
        if (session != null) return "An export is already active: " + session.status();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return "Open a client world before exporting";
        if (minecraft.getOverlay() != null) return "Wait for resource loading to finish before exporting";
        try {
            sessionResourceGeneration = RESOURCE_GENERATION.get();
            session = new ExportSession(minecraft, allowPbo);
            progressScreen = new ExportProgressScreen(
                session.status(),
                ClientBootstrap::cancel,
                ClientBootstrap::closeScreen);
            minecraft.setScreen(progressScreen);
            if (minecraft.screen != progressScreen)
                throw new IllegalStateException("Another mod prevented the export screen from opening");
            lastProgressMessage = System.nanoTime();
            return "Started full 64px icon export; use the export screen to view progress or cancel";
        } catch (RuntimeException | Error failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            if (session != null) session.cancel("could not open export screen");
            progressScreen = null;
            lastStatus = "Could not start icon export: " + failure;
            return lastStatus;
        }
    }

    @SubscribeEvent
    public static void renderTick(RenderFrameEvent.Pre event) {
        if (session == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (RESOURCE_GENERATION.get() != sessionResourceGeneration || minecraft.getOverlay() != null)
            session.cancel("resources reloaded");
        boolean complete = session.tick(minecraft);
        String status = session.status();
        if (complete) {
            lastStatus = status;
            session = null;
            if (progressScreen != null) progressScreen.complete(status);
        } else if (progressScreen != null) progressScreen.setStatus(status);
        if (minecraft.player != null
            && (complete || System.nanoTime() - lastProgressMessage >= PROGRESS_MESSAGE_INTERVAL)) {
            minecraft.player.displayClientMessage(Component.literal(status), false);
            lastProgressMessage = System.nanoTime();
        }
    }

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Pre event) {
        if (session == null) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!session.isCurrentWorld(minecraft)) {
            session.cancel("world closed or changed");
            ExportProgressScreen previous = progressScreen;
            progressScreen = null;
            if (previous != null && minecraft.screen == previous) minecraft.setScreen(null);
        }
    }

    @SubscribeEvent
    public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (session != null) session.cancel("world closed");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void screenOpening(ScreenEvent.Opening event) {
        if (progressScreen == null || event.getNewScreen() == progressScreen) return;
        if (session != null && session.isCurrentWorld(Minecraft.getInstance())) event.setCanceled(true);
        else progressScreen = null;
    }

    private static void cancel() {
        if (session != null) session.cancel("requested");
    }

    private static void closeScreen() {
        progressScreen = null;
        Minecraft.getInstance()
            .setScreen(null);
    }

    @EventBusSubscriber(modid = IconGeneratorMod.MOD_ID, value = Dist.CLIENT)
    public static final class ReloadEvents {

        private ReloadEvents() {}

        @SubscribeEvent
        public static void registerReload(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((barrier, resources, prepareProfiler, applyProfiler, background, game) -> {
                // May run off-thread. GL and session cleanup remain on the render thread.
                RESOURCE_GENERATION.incrementAndGet();
                return barrier.wait(null)
                    .thenRunAsync(() -> {}, game);
            });
        }
    }
}
