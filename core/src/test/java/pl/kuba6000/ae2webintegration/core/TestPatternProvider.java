package pl.kuba6000.ae2webintegration.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.DimensionalCoords;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IEncodedPatternDetails;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProvider;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternSlotHandle;

/** Small native provider with physical slots representing all four states used by the HTTP and live fixtures. */
final class TestPatternProvider implements IPatternProvider {

    final CpuFixtures.Resource iron = new CpuFixtures.Resource("pattern_test_iron");
    int slotReads;
    String name = "Furnace";

    @Override
    public @NotNull String web$getName() {
        return name;
    }

    @Override
    public @NotNull DimensionalCoords web$getLocation() {
        return new DimensionalCoords(0, 4, 5, 6);
    }

    @Override
    public @NotNull String web$getPatternSide() {
        return "north";
    }

    @Override
    public @NotNull IAEKey web$getPatternIcon() {
        return iron;
    }

    @Override
    public boolean web$isPatternProviderOnline() {
        return true;
    }

    @Override
    public boolean web$isPatternProviderVisible() {
        return true;
    }

    @Override
    public int web$getPatternSlotCount() {
        return 4;
    }

    @Override
    public int web$getPatternRowSize() {
        return 4;
    }

    @Override
    public @Nullable Integer web$getPatternPriority() {
        return null;
    }

    @Override
    public long web$getPatternSortValue() {
        return 0;
    }

    @Override
    public @Nullable IPatternSlotHandle web$getPattern(int slot) {
        slotReads++;
        if (slot == 0) return null;
        return new IPatternSlotHandle() {

            @Override
            public boolean web$sameAs(@NotNull IPatternSlotHandle other) {
                return this == other;
            }

            @Override
            public @Nullable IEncodedPatternDetails web$decode() {
                if (slot == 1) return null;
                return new IEncodedPatternDetails() {

                    @Override
                    public boolean web$isCraftingPattern() {
                        return slot == 2;
                    }

                    @Override
                    public IAEGenericStack @NotNull [] web$getCondensedInputs() {
                        return new IAEGenericStack[] { new Stack(iron, 4) };
                    }

                    @Override
                    public IAEGenericStack @NotNull [] web$getCondensedOutputs() {
                        return new IAEGenericStack[] { new Stack(iron, 3) };
                    }
                };
            }
        };
    }

    @Desugar
    private record Stack(@NotNull IAEKey web$what, long web$amount) implements IAEGenericStack {}
}
