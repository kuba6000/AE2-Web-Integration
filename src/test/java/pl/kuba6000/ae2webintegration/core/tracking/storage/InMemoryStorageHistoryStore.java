package pl.kuba6000.ae2webintegration.core.tracking.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Test persistence boundary for collector scheduling; real durability is covered by HSQLDB tests. */
public final class InMemoryStorageHistoryStore implements IStorageHistoryStore {

    private final Map<StableKey, ResourceDescriptor> resources = new HashMap<>();
    private final Map<StableKey, List<StorageHistoryPoint>> records = new HashMap<>();

    @Override
    public synchronized void append(@NotNull StableKey gridKey,
        @NotNull Map<StableKey, ResourceDescriptor> descriptions, @NotNull List<StorageHistoryPoint> points) {
        resources.putAll(descriptions);
        records.computeIfAbsent(gridKey, key -> new ArrayList<>())
            .addAll(points);
    }

    @Override
    public synchronized @NotNull StorageHistory read(@NotNull StableKey gridKey, @Nullable StableKey itemKey,
        long fromMillis, int limit) {
        List<StorageHistoryPoint> result = new ArrayList<>();
        boolean complete = true;
        List<StorageHistoryPoint> points = records.getOrDefault(gridKey, Collections.emptyList());
        for (StorageHistoryPoint point : points) {
            if (point.observedAt() < fromMillis
                || itemKey != null && point.itemKey() != null && !itemKey.equals(point.itemKey())) continue;
            complete &= point.kind() != StorageHistoryPoint.Kind.GAP;
            result.add(point);
        }
        boolean truncated = result.size() > limit;
        if (truncated) result = new ArrayList<>(result.subList(result.size() - limit, result.size()));
        if (!result.isEmpty()) {
            long boundary = result.get(0)
                .observedAt();
            boolean hasGap = result.stream()
                .anyMatch(point -> point.observedAt() == boundary && point.kind() == StorageHistoryPoint.Kind.GAP);
            if (!hasGap) for (StorageHistoryPoint point : points) {
                if (point.observedAt() == boundary && point.kind() == StorageHistoryPoint.Kind.GAP) {
                    result.add(0, point);
                    break;
                }
            }
        }
        return new StorageHistory(result, resources, complete, truncated);
    }
}
