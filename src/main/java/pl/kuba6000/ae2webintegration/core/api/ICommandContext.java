package pl.kuba6000.ae2webintegration.core.api;

/**
 * Platform-agnostic abstraction over a Minecraft command sender.
 * <p>
 * Interface layer implementations wrap either Forge's {@code ICommandSender}
 * or NeoForge's {@code CommandSourceStack} depending on the MC version.
 */
public interface ICommandContext {

    /** Raw arguments passed to the command (everything after the command name). */
    String[] getArgs();

    /** Complete identity of the executing player, or {@code null} for a non-player sender. */
    PlayerIdentity getPlayerIdentity();

    /** Whether the sender has the given permission level. */
    boolean hasPermission(int level);

    /** Sends a success/info message to the sender (interface adds green coloring). */
    void sendMessage(String text);

    /** Sends an error message to the sender (interface adds red coloring). */
    void sendError(String text);

    /** Returns a Runnable that triggers the platform-specific config reload. */
    Runnable getReloader();
}
