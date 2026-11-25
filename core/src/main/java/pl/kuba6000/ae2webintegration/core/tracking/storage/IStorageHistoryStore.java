package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Blocking persistence boundary. Operations may not access native game state. */
public interface IStorageHistoryStore extends AutoCloseable {

    void append(@NotNull StableKey gridKey, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
        @NotNull List<StorageHistoryPoint> points) throws IOException;

    default @NotNull StorageHistory read(@NotNull StableKey gridKey, @Nullable StableKey itemKey, long fromMillis,
        int limit) throws IOException {
        return read(gridKey, itemKey, fromMillis, Long.MAX_VALUE, limit);
    }

    @NotNull
    StorageHistory read(@NotNull StableKey gridKey, @Nullable StableKey itemKey, long fromMillis, long untilMillis,
        int limit) throws IOException;

    enum ResourceSort {

        NAME,
        NAME_DESC,
        RECENT,
        STOCK,
        INPUT,
        OUTPUT,
        INSERTS,
        EXTRACTS;

        public boolean requiresType() {
            return this == STOCK || this == INPUT || this == OUTPUT;
        }
    }

    @Desugar
    record ResourceFilter(@Nullable String search, @Nullable ResourceType type, @NotNull ResourceSort sort, long from,
        long until) {}

    /** Recorded transfer totals, encoded exactly even when multiple long-valued intervals overflow a long. */
    @Desugar
    record Activity(@NotNull String input, @NotNull String output, @NotNull String inputOperations,
        @NotNull String outputOperations) {}

    /** Last retained stock measurement; a recorded zero is distinct from an absent measurement. */
    @Desugar
    record Stock(@NotNull String quantity, long observedAt) {}

    @Desugar
    record ResourcePage(@NotNull List<ResourceDescriptor> entries, @Nullable Long nextAfter,
        @NotNull Map<String, Long> lastObservedAt, @NotNull Map<String, Activity> activity,
        @NotNull Map<String, Stock> stock) {

        public static ResourcePage empty() {
            return new ResourcePage(
                Collections.emptyList(),
                null,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap());
        }
    }

    @NotNull
    ResourcePage resources(@NotNull StableKey gridKey, @Nullable Long after, @NotNull ResourceFilter filter, int limit)
        throws IOException;

    /** Close after the persistence worker has drained; file-only stores own no open handles. */
    @Override
    default void close() throws IOException {}
}
