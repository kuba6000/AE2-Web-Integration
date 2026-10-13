package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.io.IOException;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Blocking persistence boundary. Neither operation may access native game state. */
public interface IStorageHistoryStore {

    void append(@NotNull StableKey gridKey, @NotNull List<StorageHistoryPoint> points) throws IOException;

    @NotNull
    StorageHistory read(@NotNull StableKey gridKey, @Nullable StableKey itemKey, long fromMillis, int limit)
        throws IOException;
}
