package pl.kuba6000.ae2webintegration.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.PlayerIdentity;

/**
 * Stable identity authenticated by the web layer.
 * <p>
 * A player's UUID is the account identity. The username is retained only as the canonical display and
 * login name, so a rename does not create a second authorization cache entry. Administrator and trusted
 * localhost access are explicit principals instead of magic AE2 player ids.
 */
public final class WebPrincipal {

    private enum Kind {
        PLAYER,
        ADMIN,
        LOCALHOST
    }

    private static final WebPrincipal ADMIN = new WebPrincipal(Kind.ADMIN, null, "Admin");
    private static final WebPrincipal LOCALHOST = new WebPrincipal(Kind.LOCALHOST, null, "localhost");

    private final @NotNull Kind kind;
    private final @Nullable PlayerIdentity playerIdentity;
    private final @NotNull String username;

    private WebPrincipal(@NotNull Kind kind, @Nullable PlayerIdentity playerIdentity, @NotNull String username) {
        this.kind = kind;
        this.playerIdentity = playerIdentity;
        this.username = username;
    }

    public static @NotNull WebPrincipal forPlayer(@NotNull PlayerIdentity identity) {
        return new WebPrincipal(Kind.PLAYER, identity, identity.name);
    }

    public static @NotNull WebPrincipal admin() {
        return ADMIN;
    }

    public static @NotNull WebPrincipal localhost() {
        return LOCALHOST;
    }

    public boolean isAdmin() {
        return kind != Kind.PLAYER;
    }

    public @Nullable PlayerIdentity getPlayerIdentity() {
        return playerIdentity;
    }

    public @NotNull String getUsername() {
        return username;
    }

    @Override
    public boolean equals(@Nullable Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof WebPrincipal)) {
            return false;
        }
        WebPrincipal other = (WebPrincipal) object;
        if (kind != other.kind) {
            return false;
        }
        return kind != Kind.PLAYER || playerIdentity.uuid.equals(other.playerIdentity.uuid);
    }

    @Override
    public int hashCode() {
        return kind == Kind.PLAYER ? 31 * kind.hashCode() + playerIdentity.uuid.hashCode() : kind.hashCode();
    }
}
