package pl.kuba6000.ae2webintegration.ae2interface.accessors;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import appeng.api.features.IPlayerRegistry;
import appeng.crafting.inv.ListCraftingInventory;

public interface IExecutingCraftingJobAccessor {

    ListCraftingInventory web$getWaitingFor();

    @Nullable
    Integer web$getPlayerId();

    /** Resolves only names already present on the server; UUID cache lookup never fetches a profile. */
    default @Nullable String web$getRequesterName(@NotNull MinecraftServer server) {
        Integer playerId = web$getPlayerId();
        if (playerId == null) return null;
        UUID uuid = IPlayerRegistry.getMapping(server)
            .getProfileId(playerId);
        if (uuid == null) return null;
        ServerPlayer player = server.getPlayerList()
            .getPlayer(uuid);
        if (player != null) return player.getGameProfile()
            .name();
        return server.services()
            .nameToIdCache()
            .get(uuid)
            .map(NameAndId::name)
            .orElse(null);
    }
}
