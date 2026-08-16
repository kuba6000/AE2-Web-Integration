package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import net.minecraft.world.World;

import appeng.api.networking.security.IActionHost;
import appeng.me.helpers.PlayerSource;

/** Keeps the legacy chat-error channel local to the platform adapter instead of the shared core API. */
public final class ChatCapturingPlayerSource extends PlayerSource {

    private final ChatCapturingFakePlayer capturingPlayer;

    public ChatCapturingPlayerSource(ChatCapturingFakePlayer player, IActionHost actionHost) {
        super(player, actionHost);
        this.capturingPlayer = player;
    }

    public void clearLastMessage() {
        capturingPlayer.clearLastMessage();
    }

    public String takeLastMessage() {
        return capturingPlayer.takeLastMessage();
    }

    public boolean isForWorld(World world) {
        return capturingPlayer.world == world;
    }

    public void dispose() {
        capturingPlayer.dispose();
    }
}
