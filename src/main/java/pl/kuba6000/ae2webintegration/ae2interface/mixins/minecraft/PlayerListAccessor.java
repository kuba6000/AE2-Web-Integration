package pl.kuba6000.ae2webintegration.ae2interface.mixins.minecraft;

import java.util.Map;
import java.util.UUID;

import net.minecraft.advancements.PlayerAdvancements;
import net.minecraft.server.management.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerList.class)
public interface PlayerListAccessor {

    @Accessor("advancements")
    Map<UUID, PlayerAdvancements> web$getAdvancements();
}
