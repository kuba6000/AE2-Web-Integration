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

import org.jetbrains.annotations.Nullable;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import pl.kuba6000.ae2webintegration.icongenerator.GeneratorProxy;

public final class ClientBootstrap extends GeneratorProxy implements IResourceManagerReloadListener {

    private static final long PROGRESS_MESSAGE_INTERVAL = TimeUnit.SECONDS.toNanos(5);
    private @Nullable ExportSession session;
    private long lastProgressMessage;
    private String lastStatus = "No icon export started";

    @Override
    public void initialize() {
        ClientCommandHandler.instance.registerCommand(new ExportCommand());
        FMLCommonHandler.instance()
            .bus()
            .register(this);
        ((IReloadableResourceManager) Minecraft.getMinecraft()
            .getResourceManager()).registerReloadListener(this);
    }

    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || session == null) return;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (session.tick(minecraft)) {
            lastStatus = session.status();
            session = null;
            if (minecraft.thePlayer != null) minecraft.thePlayer.addChatMessage(new ChatComponentText(lastStatus));
        } else
            if (minecraft.thePlayer != null && System.nanoTime() - lastProgressMessage >= PROGRESS_MESSAGE_INTERVAL) {
                minecraft.thePlayer.addChatMessage(new ChatComponentText(session.status()));
                lastProgressMessage = System.nanoTime();
            }
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
                lastProgressMessage = System.nanoTime();
                return "Started full 64px icon export; progress every 5 seconds, /ae2webicons status for details";
            } catch (RuntimeException exception) {
                lastStatus = "Could not start icon export: " + exception;
                return lastStatus;
            }
        }
    }
}
