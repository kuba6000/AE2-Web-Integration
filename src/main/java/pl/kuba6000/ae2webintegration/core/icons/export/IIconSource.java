package pl.kuba6000.ae2webintegration.core.icons.export;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Incremental native discovery, called only on the owning client thread. */
public interface IIconSource<C extends IIconCandidate<C>> {

    /** Returns a waiting reason, or null when ready. Partial native failures use the supplied reporter. */
    @Nullable
    String prepare(@NotNull BiConsumer<String, Throwable> failures);

    @Nullable
    C next();

    boolean done();

    void verify();

    @NotNull
    String context();

    /** Transfers a mutable statistics map; returned labels contain no native objects. */
    @NotNull
    Map<String, Long> counts();

    @NotNull
    List<String> sources();

    @NotNull
    String note();

    /** Unwraps native crash wrappers; catalogue invalidation must be thrown, never recorded as an omission. */
    @NotNull
    Throwable failureCause(@NotNull Throwable failure);
}
