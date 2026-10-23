package pl.kuba6000.ae2webintegration.core.http.dto;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.api.CpuSelectionMode;
import pl.kuba6000.ae2webintegration.core.api.CraftingRequester;
import pl.kuba6000.ae2webintegration.core.cpu.CpuObservation;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;

@SuppressWarnings("unused") // Gson reads the fields reflectively.
public class GetCPUClusterData {

    public GetCPUClusterData(@NotNull CpuObservation.Header header, long at, @Nullable IconMappings mappings) {
        size = header.size();
        isBusy = header.busy();
        selectionMode = header.selectionMode();
        supportsPause = header.supportsPause();
        isPaused = header.paused();
        finalOutput = header.finalOutput() == null ? null : new ResourceStack(header.finalOutput(), mappings);
        hasTrackingInfo = header.hasTrackingInfo();
        timeStarted = header.timeStarted();
        requesters = header.requesters();
        timeElapsed = header.timeElapsed(at);
    }

    /**
     * Total storage capacity of this CPU in bytes.
     *
     * @example 16384
     */
    public final long size;
    /**
     * Whether the CPU is currently crafting.
     *
     * @example true
     */
    public final boolean isBusy;
    /** Native automatic-selection policy; null when unsupported or unknown. Not a submission permission. */
    public final @Nullable CpuSelectionMode selectionMode;
    /** Whether this CPU supports pausing the scheduling of its current job. */
    public final boolean supportsPause;
    /** Whether the active job is paused; false for idle or unsupported CPUs. */
    public final boolean isPaused;
    /** Detached final output snapshot; null when the CPU is idle or its output is unavailable. */
    public final @Nullable ResourceStack finalOutput;
    /** Resource details for current work; null when the CPU is idle. */
    public @Nullable ArrayList<CpuResource> items;
    /**
     * Whether measurements are available for the active job.
     *
     * @example true
     */
    public final boolean hasTrackingInfo;
    /**
     * Crafting start time in Unix epoch milliseconds; zero when unavailable.
     *
     * @example 1700000000000
     */
    public final long timeStarted;
    /**
     * Elapsed crafting time in milliseconds; zero when tracking is unavailable.
     *
     * @example 10000
     */
    public final long timeElapsed;
    /** Player, automation and web requesters of the tracked job, in submission order; empty when unavailable. */
    public final @NotNull List<CraftingRequester> requesters;
}
