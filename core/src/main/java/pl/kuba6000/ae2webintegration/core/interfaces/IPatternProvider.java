package pl.kuba6000.ae2webintegration.core.interfaces;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;

/** Native provider displayed by a pattern terminal. All reads are server-thread only. */
public interface IPatternProvider extends IPatternProviderViewable {

    /** A terminal provider has a display label; job history alone permits unnamed crafting media. */
    @Override
    @NotNull
    String web$getName();

    /** A terminal provider must be addressable; the adapter omits and diagnoses unsupported devices. */
    @Override
    @NotNull
    DimensionalCoords web$getLocation();

    /** Canonical lower-case face for a multipart provider; null for a block. */
    @Nullable
    String web$getPatternSide();

    @Nullable
    IAEKey web$getPatternIcon();

    boolean web$isPatternProviderOnline();

    boolean web$isPatternProviderVisible();

    int web$getPatternSlotCount();

    int web$getPatternRowSize();

    @Nullable
    Integer web$getPatternPriority();

    long web$getPatternSortValue();

    /** Returns null for an empty slot, without decoding the pattern. */
    @Nullable
    IPatternSlotHandle web$getPattern(int slot);
}
