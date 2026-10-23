package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.multiplayer.ClientLevel;
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

    private record AnimationRequest(ClientLevel world, long resourceGeneration, boolean allowPbo) {}

    private static @Nullable AnimationRequest animationRequest;
    private static @Nullable ConfirmScreen confirmationScreen;
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
                            .executes(context -> respond(context, start(true, false)))
                            .then(
                                Commands.literal("nopbo")
                                    .executes(context -> respond(context, start(false, false)))
                                    .then(
                                        Commands.literal("animation")
                                            .executes(context -> respond(context, requestAnimation(false)))))
                            .then(
                                Commands.literal("animation")
                                    .executes(context -> respond(context, requestAnimation(true)))
                                    .then(
                                        Commands.literal("nopbo")
                                            .executes(context -> respond(context, requestAnimation(false))))))
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
        if (animationRequest != null) return "Awaiting confirmation for animated icon export";
        return session == null ? lastStatus : session.status();
    }

    private static @Nullable String unavailable(Minecraft minecraft) {
        if (session != null) return "An export is already active: " + session.status();
        if (minecraft.level == null || minecraft.player == null) return "Open a client world before exporting";
        if (minecraft.getOverlay() != null) return "Wait for resource loading to finish before exporting";
        return null;
    }

    private static String requestAnimation(boolean allowPbo) {
        Minecraft minecraft = Minecraft.getInstance();
        if (confirmationScreen != null && minecraft.screen != confirmationScreen) cancelConfirmation();
        String unavailable = unavailable(minecraft);
        if (unavailable != null) return unavailable;
        if (animationRequest != null) return "Confirm or cancel the pending animated export first";
        AnimationRequest request = new AnimationRequest(minecraft.level, RESOURCE_GENERATION.get(), allowPbo);
        animationRequest = request;
        // Forge chat closes its screen after executing a command; show the confirmation on the next client task.
        minecraft.tell(() -> showConfirmation(request));
        return "Confirm animated icon export in the dialog";
    }

    private static void showConfirmation(AnimationRequest request) {
        if (animationRequest != request) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (!isCurrentRequest(request, minecraft) || unavailable(minecraft) != null) {
            cancelConfirmation();
            return;
        }
        confirmationScreen = new ConfirmScreen(
            accepted -> confirmAnimation(request, accepted),
            Component.literal("Export animated icons?"),
            Component.literal("Capturing animations can take a long time, especially in large modpacks. Continue?"),
            Component.literal("Start export"),
            Component.literal("Cancel"));
        minecraft.setScreen(confirmationScreen);
        if (minecraft.screen != confirmationScreen) cancelConfirmation();
    }

    private static boolean isCurrentRequest(AnimationRequest request, Minecraft minecraft) {
        return minecraft.level == request.world() && RESOURCE_GENERATION.get() == request.resourceGeneration();
    }

    private static void confirmAnimation(AnimationRequest request, boolean accepted) {
        Minecraft minecraft = Minecraft.getInstance();
        if (animationRequest != request) return;
        if (minecraft.screen != confirmationScreen) {
            cancelConfirmation();
            return;
        }
        cancelConfirmation();
        if (!accepted) return;
        String response = isCurrentRequest(request, minecraft) ? start(request.allowPbo(), true)
            : "World or resources changed; request the export again";
        if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal(response), false);
    }

    private static void cancelConfirmation() {
        animationRequest = null;
        ConfirmScreen previous = confirmationScreen;
        confirmationScreen = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (previous != null && minecraft.screen == previous) minecraft.setScreen(null);
    }

    private static String start(boolean allowPbo, boolean animated) {
        Minecraft minecraft = Minecraft.getInstance();
        String unavailable = unavailable(minecraft);
        if (unavailable != null) return unavailable;
        cancelConfirmation();
        try {
            sessionResourceGeneration = RESOURCE_GENERATION.get();
            session = new ExportSession(minecraft, allowPbo, animated);
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
        Minecraft minecraft = Minecraft.getInstance();
        if (animationRequest != null
            && (!isCurrentRequest(animationRequest, minecraft) || minecraft.getOverlay() != null
                || confirmationScreen != null && minecraft.screen != confirmationScreen))
            cancelConfirmation();
        if (session == null) return;
        if (!session.isCurrentWorld(minecraft)) {
            session.cancel("world closed or changed");
            ExportProgressScreen previous = progressScreen;
            progressScreen = null;
            if (previous != null && minecraft.screen == previous) minecraft.setScreen(null);
        }
    }

    @SubscribeEvent
    public static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        cancelConfirmation();
        if (session != null) session.cancel("world closed");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void screenOpening(ScreenEvent.Opening event) {
        if (confirmationScreen != null && event.getNewScreen() != confirmationScreen) {
            animationRequest = null;
            confirmationScreen = null;
        }
        if (progressScreen == null || event.getNewScreen() == progressScreen) return;
        if (session != null && session.isCurrentWorld(Minecraft.getInstance())) event.setCanceled(true);
        else progressScreen = null;
    }

    private static void cancel() {
        cancelConfirmation();
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
