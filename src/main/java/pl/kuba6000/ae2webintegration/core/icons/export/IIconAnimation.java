package pl.kuba6000.ae2webintegration.core.icons.export;

import java.util.List;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.NotNull;

/** Client-owned native plan. Keep scratch scoped to a step, not retained for the whole candidate group. */
public interface IIconAnimation extends AutoCloseable {

    enum Kind {
        SPRITE,
        SAMPLED
    }

    @NotNull
    Kind kind();

    /**
     * Submits at most one icon; deferred readback may append zero immediate records. Return false after
     * the last step, including an early-completing sampled cursor. One sampled image stays static.
     * Use drawClock at actual render submission and carry that metadata through deferred readback.
     * Restore all native state before returning. Only a safely restored draw may throw RenderFailure.
     * Native plans bound their virtual timeline and finish normally; the shared 256-step guard omits
     * an overrun rather than publishing a truncated clip.
     */
    boolean capture(@NotNull List<PackExportWriter.Capture> captures, @NotNull LongSupplier drawClock)
        throws IIconCapture.RenderFailure;

    /** Restore/release native ownership; cleanup failures are fatal, never per-item omissions. */
    @Override
    void close();
}
