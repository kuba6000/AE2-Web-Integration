package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/** Test persistence boundary for collector scheduling; real durability is covered by HSQLDB tests. */
public final class InMemoryStorageHistoryStore implements IStorageHistoryStore {

    private final Map<StableKey, List<StorageHistoryPoint>> records = new HashMap<>();

    @Override
    public synchronized void append(@NotNull StableKey gridKey, @NotNull List<StorageHistoryPoint> points) {
        records.computeIfAbsent(gridKey, key -> new ArrayList<>())
            .addAll(points);
    }

    @Override
    public synchronized @NotNull StorageHistory read(@NotNull StableKey gridKey, @Nullable StableKey itemKey,
        long fromMillis, int limit) {
        List<StorageHistoryPoint> result = new ArrayList<>();
        boolean complete = true;
        List<StorageHistoryPoint> points = records.get(gridKey);
        if (points != null) {
            for (StorageHistoryPoint point : points) {
                if (point.observedAt() < fromMillis || itemKey != null && point.resource() != null
                    && !itemKey.equals(
                        point.resource()
                            .itemKey()))
                    continue;
                complete &= point.complete();
                result.add(point);
            }
        }
        boolean truncated = result.size() > limit;
        if (truncated) result = new ArrayList<>(result.subList(result.size() - limit, result.size()));
        return new StorageHistory(result, complete, truncated);
    }
}
