package pl.kuba6000.ae2webintegration.core.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

/**
 * Who requested a crafting job.
 * 
 * @param source player, native automation, or web submission without a Minecraft identity
 * @param player Minecraft UUID and last observed name; present only for PLAYER
 */
@Desugar
public record CraftingRequester(@NotNull Source source, @Nullable PlayerIdentity player) {

    public enum Source {
        PLAYER,
        AUTOMATION,
        WEB
    }

    public static @NotNull CraftingRequester player(@NotNull PlayerIdentity player) {
        return new CraftingRequester(Source.PLAYER, player);
    }

    public static @NotNull CraftingRequester automation() {
        return new CraftingRequester(Source.AUTOMATION, null);
    }

    public static @NotNull CraftingRequester web() {
        return new CraftingRequester(Source.WEB, null);
    }
}
