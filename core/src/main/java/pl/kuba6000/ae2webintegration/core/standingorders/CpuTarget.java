package pl.kuba6000.ae2webintegration.core.standingorders;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * Which crafting CPU runs the job.
 *
 * @param cpu         the selected CPU, or null for automatic selection
 * @param machineOnly automatically select only CPUs explicitly reserved for automation
 */
@Desugar
public record CpuTarget(@Nullable StableKey cpu, boolean machineOnly) {

    public CpuTarget {
        if (cpu != null && machineOnly) throw new IllegalArgumentException("A machine pool has no fixed CPU key");
    }

    private static final CpuTarget ANY = new CpuTarget(null, false);

    public static @NotNull CpuTarget machines() {
        return new CpuTarget(null, true);
    }

    public static @NotNull CpuTarget any() {
        return ANY;
    }

    public static @NotNull CpuTarget specific(@NotNull StableKey cpu) {
        return new CpuTarget(cpu, false);
    }
}
