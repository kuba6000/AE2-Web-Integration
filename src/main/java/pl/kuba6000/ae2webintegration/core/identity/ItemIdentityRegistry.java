package pl.kuba6000.ae2webintegration.core.identity;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Server-thread-only shared identities retained by the grids that last reported them. */
public final class ItemIdentityRegistry {

    // Values must not refer back to their grid: that would defeat the weak owner key.
    private final Cache<IAEGrid, Set<Entry>> owners = CacheBuilder.newBuilder()
        .weakKeys()
        .build();
    private final Cache<StableItemKey, Entry> entries = CacheBuilder.newBuilder()
        .weakValues()
        .build();
    private final Cache<IAEKey, Entry> reverse = CacheBuilder.newBuilder()
        .weakValues()
        .build();
    // Remember observed conflicts until world teardown, without retaining native resource data.
    private final Set<StableItemKey> ambiguous = new HashSet<>();

    /** Retain an output identity until the grid next publishes its complete item list. */
    public @NotNull StableItemKey remember(@NotNull IAEGrid grid, @NotNull IAEKey resource) throws IOException {
        cleanUp();
        Entry entry = findOrCreate(resource);
        owners.asMap()
            .computeIfAbsent(grid, ignored -> new HashSet<>())
            .add(entry);
        return entry.key;
    }

    /** Start a replacement list; the previous list stays owned until commit succeeds. */
    public @NotNull Listing beginListing(@NotNull IAEGrid grid) {
        cleanUp();
        return new Listing(grid);
    }

    public @Nullable IAEKey resolve(@NotNull StableItemKey key) {
        cleanUp();
        if (ambiguous.contains(key)) throw new Ambiguous();
        Entry entry = entries.getIfPresent(key);
        return entry == null ? null : entry.identity;
    }

    public void clear() {
        owners.invalidateAll();
        entries.invalidateAll();
        reverse.invalidateAll();
        ambiguous.clear();
    }

    private @NotNull Entry findOrCreate(@NotNull IAEKey resource) throws IOException {
        Entry remembered = reverse.getIfPresent(resource);
        if (remembered != null) {
            if (ambiguous.contains(remembered.key)) throw new Ambiguous();
            return remembered;
        }
        byte[] body = resource.web$getIdentityBytes();
        StableItemKey key = StableItemKey.fromIdentityBytes(body);
        if (ambiguous.contains(key)) throw new Ambiguous();
        Entry existing = entries.getIfPresent(key);
        if (existing != null) {
            if (!Arrays.equals(body, existing.bytes) || !existing.identity.equals(resource)) {
                entries.invalidate(key);
                reverse.invalidate(existing.identity);
                ambiguous.add(key);
                throw new Ambiguous();
            }
            return existing;
        }
        IAEKey copy = resource.web$copyIdentity();
        if (!resource.equals(copy) || !Arrays.equals(body, copy.web$getIdentityBytes()))
            throw new IOException("Identity copy changed the resource");
        Entry entry = new Entry(key, copy, body);
        entries.put(key, entry);
        reverse.put(copy, entry);
        return entry;
    }

    private void cleanUp() {
        // Weak grid keys use instance identity. Remove collected owners before cleaning global
        // weak-value indexes; neither operation scans the live item catalogues.
        owners.cleanUp();
        entries.cleanUp();
        reverse.cleanUp();
    }

    /** Temporary ownership for one synchronous server-thread traversal; never retain across world cleanup. */
    public final class Listing {

        private IAEGrid grid;
        private Set<Entry> collected = new HashSet<>();

        private Listing(@NotNull IAEGrid grid) {
            this.grid = grid;
        }

        public @NotNull StableItemKey remember(@NotNull IAEKey resource) throws IOException {
            requireOpen();
            Entry entry = findOrCreate(resource);
            collected.add(entry);
            return entry.key;
        }

        public void commit() {
            requireOpen();
            owners.put(grid, collected);
            collected = null;
            grid = null;
        }

        private void requireOpen() {
            if (collected == null) throw new IllegalStateException("Listing already committed");
        }
    }

    /** An observed conflict must never silently select either resource. */
    public static final class Ambiguous extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private Ambiguous() {
            super("Ambiguous resource identity");
        }
    }

    private static final class Entry {

        private final StableItemKey key;
        private final IAEKey identity;
        private final byte[] bytes;

        private Entry(@NotNull StableItemKey key, @NotNull IAEKey identity, byte @NotNull [] bytes) {
            this.key = key;
            this.identity = identity;
            this.bytes = bytes.clone();
        }
    }
}
