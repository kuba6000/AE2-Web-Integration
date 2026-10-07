package pl.kuba6000.ae2webintegration.core.identity;

import java.util.HashSet;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IIdentityHolder;

/** Server-thread-only shared identities retained by the grids, CPUs and plans that last reported them. */
public final class ItemIdentityRegistry {

    // Values must not refer back to their owner: that would defeat the weak owner key.
    private final Cache<IIdentityHolder, Set<Entry>> owners = CacheBuilder.newBuilder()
        .weakKeys()
        .build();
    private final Cache<StableKey, Entry> entries = CacheBuilder.newBuilder()
        .weakValues()
        .build();
    private final Cache<IAEKey, Entry> reverse = CacheBuilder.newBuilder()
        .weakValues()
        .build();
    // Remember observed conflicts until world teardown, without retaining native resource data.
    private final Set<StableKey> ambiguous = new HashSet<>();

    /** Retain an output identity until its owner next publishes a complete resource snapshot. */
    public @NotNull StableKey remember(@NotNull IIdentityHolder owner, @NotNull IAEKey resource) {
        cleanUp();
        Entry entry = findOrCreate(resource);
        owners.asMap()
            .computeIfAbsent(owner, ignored -> new HashSet<>())
            .add(entry);
        return entry.key;
    }

    /** Start a replacement list; the previous list stays owned until commit succeeds. */
    public @NotNull Listing beginListing(@NotNull IIdentityHolder owner) {
        cleanUp();
        return new Listing(owner);
    }

    /** Resolve only after an exact icon miss; absence is memoized for the retained identity too. */
    public @Nullable StableKey resolveIconBase(@NotNull StableKey key) {
        cleanUp();
        if (ambiguous.contains(key)) throw new Ambiguous();
        Entry entry = entries.getIfPresent(key);
        if (entry == null) return null;
        if (!entry.baseComputed) {
            try {
                entry.base = entry.identity.web$getIconBaseKey();
            } catch (RuntimeException unavailable) {
                // A failing optional native normalizer must neither break the listing nor run every poll.
                entry.base = null;
            }
            entry.baseComputed = true;
        }
        return entry.base;
    }

    public @Nullable IAEKey resolve(@NotNull StableKey key) {
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

    private @NotNull Entry findOrCreate(@NotNull IAEKey resource) {
        Entry remembered = reverse.getIfPresent(resource);
        if (remembered != null) {
            if (ambiguous.contains(remembered.key)) throw new Ambiguous();
            return remembered;
        }
        IAEKey copy = resource.web$copyIdentity();
        StableKey key = copy.web$getKey();
        if (ambiguous.contains(key)) throw new Ambiguous();
        Entry existing = entries.getIfPresent(key);
        if (existing != null) {
            if (!existing.identity.equals(copy)) {
                entries.invalidate(key);
                reverse.invalidate(existing.identity);
                ambiguous.add(key);
                throw new Ambiguous();
            }
            return existing;
        }
        Entry entry = new Entry(key, copy);
        entries.put(key, entry);
        reverse.put(copy, entry);
        return entry;
    }

    private void cleanUp() {
        // Weak owner keys use instance identity. Remove collected owners before cleaning global
        // weak-value indexes; neither operation scans the live item catalogues.
        owners.cleanUp();
        entries.cleanUp();
        reverse.cleanUp();
    }

    /** Temporary ownership for one synchronous server-thread traversal; never retain across world cleanup. */
    public final class Listing {

        private IIdentityHolder owner;
        private Set<Entry> collected = new HashSet<>();

        private Listing(@NotNull IIdentityHolder owner) {
            this.owner = owner;
        }

        public @NotNull StableKey remember(@NotNull IAEKey resource) {
            requireOpen();
            Entry entry = findOrCreate(resource);
            collected.add(entry);
            return entry.key;
        }

        public void commit() {
            requireOpen();
            owners.put(owner, collected);
            collected = null;
            owner = null;
        }

        private void requireOpen() {
            if (collected == null) throw new IllegalStateException("Listing already committed");
        }
    }

    /** An observed conflict must never silently select either resource. */
    public static final class Ambiguous extends IllegalArgumentException {

        @SuppressWarnings("MissingSerialAnnotation") // @Serial is unavailable on Java 8.
        private static final long serialVersionUID = 1L;

        private Ambiguous() {
            super("Ambiguous resource identity");
        }
    }

    private static final class Entry {

        private final StableKey key;
        private final IAEKey identity;
        private boolean baseComputed;
        private @Nullable StableKey base;

        private Entry(@NotNull StableKey key, @NotNull IAEKey identity) {
            this.key = key;
            this.identity = identity;
        }
    }
}
