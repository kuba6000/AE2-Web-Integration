package pl.kuba6000.ae2webintegration.core.interfaces;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Server-thread-only slot identity and lazy decoding. Only an active reader may retain a handle. */
public interface IPatternSlotHandle {

    /** Compare the captured contents (without decoding); a live stack alone cannot detect in-place edits. */
    boolean web$sameAs(@NotNull IPatternSlotHandle other);

    /** Returns null for a present stack which cannot be decoded as a pattern. */
    @Nullable
    IEncodedPatternDetails web$decode();
}
