package pl.kuba6000.ae2webintegration.core.live;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;

/** Frozen detached data drained for one subscriber. Encoding runs outside the hub monitor. */
public interface LiveUpdate {

    /** Whether the body carries resource descriptions, which are the only reason to build icon mappings. */
    boolean describes();

    /** Builds the detached event body on the SSE writer outside the hub monitor, using response-owned icons. */
    @NotNull
    IResponseBody body(@Nullable IconMappings mappings);
}
