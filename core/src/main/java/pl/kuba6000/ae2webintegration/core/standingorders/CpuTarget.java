package pl.kuba6000.ae2webintegration.core.standingorders;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * Which crafting CPU runs the job.
 *
 * @param cpu the selected CPU; null lets AE2 choose any eligible CPU
 */
@Desugar
public record CpuTarget(@Nullable StableKey cpu) {

    private static final CpuTarget ANY = new CpuTarget(null);

    public static @NotNull CpuTarget any() {
        return ANY;
    }

    public static @NotNull CpuTarget specific(@NotNull StableKey cpu) {
        return new CpuTarget(cpu);
    }
}
