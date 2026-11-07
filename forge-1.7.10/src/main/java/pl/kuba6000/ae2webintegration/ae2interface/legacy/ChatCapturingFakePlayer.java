package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;

import com.mojang.authlib.GameProfile;

/** A fake player that exposes messages emitted by legacy AE2 submission failures. */
public final class ChatCapturingFakePlayer extends FakePlayer {

    private String lastMessage;

    public ChatCapturingFakePlayer(WorldServer world, GameProfile profile) {
        super(world, profile);
    }

    @Override
    public void addChatMessage(IChatComponent message) {
        lastMessage = message.getUnformattedText();
    }

    public void clearLastMessage() {
        lastMessage = null;
    }

    public String takeLastMessage() {
        String message = lastMessage;
        lastMessage = null;
        return message;
    }

    public void dispose() {
        lastMessage = null;
    }
}
