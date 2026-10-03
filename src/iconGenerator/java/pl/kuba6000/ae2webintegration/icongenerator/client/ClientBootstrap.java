package pl.kuba6000.ae2webintegration.icongenerator.client;

import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.jetbrains.annotations.Nullable;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import pl.kuba6000.ae2webintegration.icongenerator.GeneratorProxy;

public final class ClientBootstrap extends GeneratorProxy implements IResourceManagerReloadListener {

    private static final long PROGRESS_MESSAGE_INTERVAL = TimeUnit.SECONDS.toNanos(5);
    private @Nullable ExportSession session;
    private @Nullable ExportProgressScreen progressScreen;
    private long lastProgressMessage;
    private String lastStatus = "No icon export started";

    @Override
    public void initialize() {
        ClientCommandHandler.instance.registerCommand(new ExportCommand());
        FMLCommonHandler.instance()
            .bus()
            .register(this);
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
        if (minecraft.thePlayer != null
            && (complete || System.nanoTime() - lastProgressMessage >= PROGRESS_MESSAGE_INTERVAL)) {
            minecraft.thePlayer.addChatMessage(new ChatComponentText(status));
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
        if (progressScreen == null || event.gui == progressScreen) return;
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
    public void onResourceManagerReload(IResourceManager resources) {
        if (session != null) session.cancel("resources reloaded");
    }

    private final class ExportCommand extends CommandBase {

        @Override
        public String getCommandName() {
            return "ae2webicons";
        }

        @Override
        public String getCommandUsage(ICommandSender sender) {
            return "/ae2webicons <export|status|cancel>";
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 0;
        }

        @Override
        public void processCommand(ICommandSender sender, String[] arguments) {
            String response;
            if (arguments.length != 1) {
                response = getCommandUsage(sender);
            } else if ("status".equals(arguments[0])) {
                response = session == null ? lastStatus : session.status();
            } else if ("cancel".equals(arguments[0])) {
                if (session != null) session.cancel("requested");
                response = session == null ? lastStatus : session.status();
            } else if ("export".equals(arguments[0])) {
                response = start();
            } else {
                response = getCommandUsage(sender);
            }
            sender.addChatMessage(new ChatComponentText(response));
        }

        private String start() {
            if (session != null) return "An export is already active: " + session.status();
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft.theWorld == null || minecraft.thePlayer == null)
                return "Open a client world before exporting";
            if (!OpenGlHelper.isFramebufferEnabled()) return "Enable framebuffer rendering before exporting";
            try {
                session = new ExportSession(minecraft);
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
