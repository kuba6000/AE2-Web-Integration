package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Blocking persistence boundary. Operations may not access native game state. */
public interface IStorageHistoryStore extends AutoCloseable {

    void append(@NotNull StableKey gridKey, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
        @NotNull List<StorageHistoryPoint> points, int retentionDays) throws IOException;

    /** Changes an existing history policy without requiring another measurement. */
    void setRetention(@NotNull StableKey gridKey, int retentionDays) throws IOException;

    @NotNull
    StorageHistory read(@NotNull StableKey gridKey, @Nullable StableKey itemKey, long fromMillis, int limit)
        throws IOException;

    /** Close after the persistence worker has drained; file-only stores own no open handles. */
    @Override
    default void close() throws IOException {}
}
