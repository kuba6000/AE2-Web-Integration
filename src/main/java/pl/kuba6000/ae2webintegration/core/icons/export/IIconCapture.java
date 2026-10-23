package pl.kuba6000.ae2webintegration.core.icons.export;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Client-thread capture. Only detached pixels are handed to the shared writer after drain. */
public interface IIconCapture<C extends IIconCandidate<C>> extends AutoCloseable {

    void capture(@NotNull C candidate, @NotNull StableKey key, @NotNull List<PackExportWriter.Capture> captures)
        throws RenderFailure;

    void drain(@NotNull List<PackExportWriter.Capture> captures);

    /**
     * Classifies the successful static submission without rendering it again. Null means static.
     * Returned plans own native references only; allocate image/GPU scratch lazily during a step.
     */
    default @Nullable IIconAnimation animation(@NotNull C candidate, @NotNull StableKey key) throws RenderFailure {
        return null;
    }

    void statistics(@NotNull Map<String, Long> counts);

    @Override
    void close();

    /** An isolated native draw failure; allocation, readback and state failures abort the export instead. */
    final class RenderFailure extends Exception {

        public RenderFailure(@NotNull Throwable cause) {
            super("Native icon renderer failed", cause);
        }
    }
}
