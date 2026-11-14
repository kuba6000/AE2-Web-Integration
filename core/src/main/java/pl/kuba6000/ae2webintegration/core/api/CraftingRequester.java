package pl.kuba6000.ae2webintegration.core.api;

import java.util.Objects;
import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * Who requested a crafting job.
 * 
 * @param source    player, native automation, web submission without a Minecraft identity, or a standing order
 * @param player    Minecraft UUID and last observed name; present only for PLAYER
 * @param orderKey  key of the standing order; present only for STANDING_ORDER
 * @param orderName name of the standing order when the job was submitted; present only for STANDING_ORDER
 */
@Desugar
public record CraftingRequester(@NotNull Source source, @Nullable PlayerIdentity player, @Nullable StableKey orderKey,
    @Nullable String orderName) {

    /** Longest persisted standing order name, in characters; the crafting history column enforces it. */
    public static final int MAX_ORDER_NAME_LENGTH = 128;

    public enum Source {
        PLAYER,
        AUTOMATION,
        WEB,
        STANDING_ORDER
    }

    public CraftingRequester {
        boolean isOrder = source == Source.STANDING_ORDER;
        if (isMissingOrPresent(source == Source.PLAYER, player) || isMissingOrPresent(isOrder, orderKey)
            || isMissingOrPresent(isOrder, orderName)) {
            throw new IllegalArgumentException("Requester identity does not match source " + source);
        }
        // Only what the history table accepts, so every stored requester loads; the name rules of an order are
        // enforced where orders are created and edited.
        if (orderName != null && (orderName.trim()
            .isEmpty() || orderName.length() > MAX_ORDER_NAME_LENGTH)) {
            throw new IllegalArgumentException(
                "Standing order name must be 1 to " + MAX_ORDER_NAME_LENGTH + " characters");
        }
    }

    public static @NotNull CraftingRequester player(@NotNull PlayerIdentity player) {
        return new CraftingRequester(Source.PLAYER, player, null, null);
    }

    public static @NotNull CraftingRequester automation() {
        return new CraftingRequester(Source.AUTOMATION, null, null, null);
    }

    public static @NotNull CraftingRequester web() {
        return new CraftingRequester(Source.WEB, null, null, null);
    }

    public static @NotNull CraftingRequester standingOrder(@NotNull StableKey orderKey, @NotNull String orderName) {
        return new CraftingRequester(Source.STANDING_ORDER, null, orderKey, orderName);
    }

    /**
     * Whether both describe the same requester of a job: the same player by UUID, the same standing order by key, or
     * the same source for the identity-less ones. Names are display data and do not take part.
     */
    public boolean isSameRequester(@NotNull CraftingRequester other) {
        UUID playerUuid = player == null ? null : player.uuid;
        UUID otherUuid = other.player == null ? null : other.player.uuid;
        return source == other.source && Objects.equals(playerUuid, otherUuid)
            && Objects.equals(orderKey, other.orderKey);
    }

    /** A value that the source requires is absent, or one that it forbids is present. */
    private static boolean isMissingOrPresent(boolean required, @Nullable Object value) {
        return required == (value == null);
    }
}
