package pl.kuba6000.ae2webintegration.core.icons.export;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Client-owned native identity, compared before any rendering can mutate its representation. */
public interface IIconCandidate<C extends IIconCandidate<C>> {

    @NotNull
    StableKey key();

    @Nullable
    C baseline();

    boolean sameIdentity(@NotNull C other);

    @NotNull
    String context();
}
