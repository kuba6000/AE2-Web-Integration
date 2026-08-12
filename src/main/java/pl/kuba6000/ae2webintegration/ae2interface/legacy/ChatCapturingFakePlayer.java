package pl.kuba6000.ae2webintegration.ae2interface.legacy;

import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import com.mojang.authlib.GameProfile;

import pl.kuba6000.ae2webintegration.ae2interface.mixins.minecraft.PlayerListAccessor;

/** A fake player that exposes messages emitted by legacy AE2 submission failures. */
public final class ChatCapturingFakePlayer extends FakePlayer {

    private ITextComponent lastMessage;

    public ChatCapturingFakePlayer(WorldServer world, GameProfile profile) {
        super(world, profile);
        detachAdvancements();
    }

    @Override
    public void sendMessage(ITextComponent message) {
        lastMessage = message;
    }

    @Override
    public void sendStatusMessage(ITextComponent message, boolean actionBar) {
        lastMessage = message;
    }

    public void clearLastMessage() {
        lastMessage = null;
    }

    public String takeLastMessage() {
        ITextComponent message = lastMessage;
        lastMessage = null;
        return message == null ? null : message.getUnformattedText();
    }

    public void dispose() {
        detachAdvancements();
        lastMessage = null;
    }

    private void detachAdvancements() {
        // EntityPlayerMP registers the PlayerAdvancements instance in both static CriteriaTriggers
        // and PlayerList. Fake players cannot earn advancements, so remove both retention paths.
        getAdvancements().dispose();
        ((PlayerListAccessor) FMLCommonHandler.instance()
            .getMinecraftServerInstance()
            .getPlayerList()).web$getAdvancements()
                .remove(getUniqueID(), getAdvancements());
    }
}
