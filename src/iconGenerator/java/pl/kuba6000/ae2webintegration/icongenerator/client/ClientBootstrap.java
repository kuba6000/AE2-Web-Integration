package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.icongenerator.GeneratorProxy;

// Every resource reload can invalidate captured models, textures or the JEI catalogue; observe all reload types.
public final class ClientBootstrap extends GeneratorProxy implements IResourceManagerReloadListener {

    private static final long PROGRESS_MESSAGE_INTERVAL = TimeUnit.SECONDS.toNanos(5);
    private @Nullable ExportSession session;
    private @Nullable ExportProgressScreen progressScreen;
    private long lastProgressMessage;
    private String lastStatus = "No icon export started";

    @Override
    public void initialize() {
        ClientCommandHandler.instance.registerCommand(new ExportCommand());
        MinecraftForge.EVENT_BUS.register(this);
        ((IReloadableResourceManager) Minecraft.getMinecraft()
            .getResourceManager()).registerReloadListener(this);
    }

    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || session == null) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        boolean complete = session.tick(minecraft);
        String status = session.status();
        if (complete) {
            lastStatus = status;
            session = null;
            if (progressScreen != null) progressScreen.complete(status);
        } else if (progressScreen != null) {
            progressScreen.setStatus(status);
        }
        if (minecraft.player != null
            && (complete || System.nanoTime() - lastProgressMessage >= PROGRESS_MESSAGE_INTERVAL)) {
            minecraft.player.sendMessage(new TextComponentString(status));
            lastProgressMessage = System.nanoTime();
        }
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START || session == null) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (!session.isCurrentWorld(minecraft)) {
            session.cancel("world closed or changed");
            ExportProgressScreen previous = progressScreen;
            progressScreen = null;
            if (previous != null && minecraft.currentScreen == previous) minecraft.displayGuiScreen(null);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void guiOpened(GuiOpenEvent event) {
        if (progressScreen == null || event.getGui() == progressScreen) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (session != null && session.isCurrentWorld(minecraft)) {
            // GuiChat closes itself immediately after submitting the export command.
            event.setCanceled(true);
        } else {
            progressScreen = null;
        }
    }

    private void cancel() {
        if (session != null) session.cancel("requested");
    }

    private void closeScreen() {
        Minecraft minecraft = Minecraft.getMinecraft();
        progressScreen = null;
        minecraft.displayGuiScreen(null);
    }

    @Override
    public void onResourceManagerReload(@NotNull IResourceManager resources) {
        if (session != null) session.cancel("resources reloaded");
        if (net.minecraftforge.fml.common.Loader.isModLoaded("jei")) JeiCatalogue.invalidate();
    }

    private final class ExportCommand extends CommandBase {

        @Override
        public @NotNull String getName() {
            return "ae2webicons";
        }

        @Override
        public @NotNull String getUsage(@NotNull ICommandSender sender) {
            return "/ae2webicons <export [nopbo]|status|cancel>";
        }

        // ClientCommandHandler supplies no integrated server on a multiplayer connection.
        @Override
        public @NotNull List<String> getTabCompletions(@Nullable MinecraftServer server, @NotNull ICommandSender sender,
            String @NotNull [] arguments, @Nullable BlockPos target) {
            if (arguments.length == 1) return getListOfStringsMatchingLastWord(arguments, "export", "status", "cancel");
            if (arguments.length == 2 && "export".equals(arguments[0]))
                return getListOfStringsMatchingLastWord(arguments, "nopbo");
            return Collections.emptyList();
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public void execute(@Nullable MinecraftServer server, @NotNull ICommandSender sender,
            String @NotNull [] arguments) {
            String response;
            if (arguments.length >= 1 && "export".equals(arguments[0])
                && (arguments.length == 1 || arguments.length == 2 && "nopbo".equals(arguments[1]))) {
                response = start(arguments.length == 1);
            } else if (arguments.length != 1) {
                response = getUsage(sender);
            } else if ("status".equals(arguments[0])) {
                response = session == null ? lastStatus : session.status();
            } else if ("cancel".equals(arguments[0])) {
                if (session != null) session.cancel("requested");
                response = session == null ? lastStatus : session.status();
            } else {
                response = getUsage(sender);
            }
            sender.sendMessage(new TextComponentString(response));
        }

        private String start(boolean allowPbo) {
            if (session != null) return "An export is already active: " + session.status();
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft.world == null || minecraft.player == null) return "Open a client world before exporting";
            if (!OpenGlHelper.isFramebufferEnabled()) return "Enable framebuffer rendering before exporting";
            try {
                session = new ExportSession(minecraft, allowPbo);
                progressScreen = new ExportProgressScreen(
                    session.status(),
                    ClientBootstrap.this::cancel,
                    ClientBootstrap.this::closeScreen);
                minecraft.displayGuiScreen(progressScreen);
                if (minecraft.currentScreen != progressScreen)
                    throw new IllegalStateException("Another mod prevented the export screen from opening");
                lastProgressMessage = System.nanoTime();
                return "Started full 64px icon export; use the export screen to view progress or cancel";
            } catch (RuntimeException | Error exception) {
                if (exception instanceof VirtualMachineError fatal) throw fatal;
                if (exception instanceof ThreadDeath fatal) throw fatal;
                if (session != null) session.cancel("could not open export screen");
                progressScreen = null;
                lastStatus = "Could not start icon export: " + exception;
                return lastStatus;
            }
        }
    }
}
