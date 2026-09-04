package pl.kuba6000.ae2webintegration.core.identity;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Server-thread-only remembered resources, independent of any grid's inventory or recipes. */
public final class ItemIdentityRegistry {

    private final Map<StableItemKey, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<IAEKey, StableItemKey> reverse = new HashMap<>();
    private final Set<StableItemKey> ambiguous = new HashSet<>();
    private final Map<Integer, StableItemKey> legacyAliases = new HashMap<>();
    private boolean legacyDisabled;
    private final int maxEntries;
    private final long maxBytes;
    private final long idleMillis;
    private final LongSupplier clock;
    private long retainedBytes;

    public ItemIdentityRegistry(int maxEntries, long maxBytes, long idleMillis, LongSupplier clock) {
        if (maxEntries <= 0 || maxBytes <= 0 || idleMillis <= 0)
            throw new IllegalArgumentException("Invalid registry limits");
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.idleMillis = idleMillis;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StableItemKey remember(IAEKey resource) throws IOException {
        StableItemKey remembered = reverse.get(resource);
        if (remembered != null && resolve(remembered) != null) return remembered;
        expire(16);
        byte[] body = resource.web$getIdentityBytes();
        StableItemKey key = StableItemKey.fromIdentityBytes(body);
        if (ambiguous.contains(key)) throw new Ambiguous();
        Entry existing = entries.get(key);
        if (existing != null) {
            if (!Arrays.equals(body, existing.bytes) || !existing.identity.equals(resource)) {
                entries.remove(key);
                release(existing);
                ambiguous.add(key);
                retainedBytes += 96;
                throw new Ambiguous();
            }
            existing.touched = clock.getAsLong();
            return key;
        }
        if (entries.size() + ambiguous.size() >= maxEntries) throw new IdentityLimitException();
        // Admission estimate includes both maps and native copies; actual heap overhead needs native profiling.
        long cost = 512L + 8L * body.length;
        if (cost > maxBytes - retainedBytes) throw new IdentityLimitException();
        IAEKey copy = resource.web$copyIdentity();
        if (!resource.equals(copy) || !Arrays.equals(body, copy.web$getIdentityBytes()))
            throw new IOException("Identity copy changed the resource");
        entries.put(key, new Entry(copy, body, clock.getAsLong()));
        reverse.put(copy, key);
        retainedBytes += cost;
        return key;
    }

    public IAEKey resolve(StableItemKey key) {
        if (ambiguous.contains(key)) throw new Ambiguous();
        Entry entry = entries.get(key);
        if (entry == null) return null;
        long now = clock.getAsLong();
        if (now - entry.touched >= idleMillis) {
            entries.remove(key);
            release(entry);
            return null;
        }
        entry.touched = now;
        return entry.identity;
    }

    /** Remember observed old hashes without allowing reassignment after resource expiry. */
    public void rememberLegacy(int oldHash, StableItemKey key) {
        Objects.requireNonNull(key, "key");
        recordLegacy(oldHash, key);
    }

    /** A displayed row without usable identity must not accidentally reuse another resource's old hash. */
    public void rejectLegacy(int oldHash) {
        recordLegacy(oldHash, null);
    }

    private void recordLegacy(int oldHash, StableItemKey key) {
        if (legacyDisabled) return;
        if (legacyAliases.containsKey(oldHash)) {
            if (key == null || !key.equals(legacyAliases.get(oldHash))) legacyAliases.put(oldHash, null);
            return;
        }
        if (legacyAliases.size() >= maxEntries || 96 > maxBytes - retainedBytes) {
            legacyDisabled = true;
            retainedBytes -= 96L * legacyAliases.size();
            legacyAliases.clear();
            return;
        }
        legacyAliases.put(oldHash, key);
        retainedBytes += 96;
    }

    public IAEKey resolveLegacy(int oldHash) throws IdentityLimitException {
        if (legacyDisabled) throw new IdentityLimitException();
        StableItemKey key = legacyAliases.get(oldHash);
        if (key == null && legacyAliases.containsKey(oldHash)) throw new Ambiguous();
        return key == null ? null : resolve(key);
    }

    public void clear() {
        entries.clear();
        reverse.clear();
        ambiguous.clear();
        legacyAliases.clear();
        legacyDisabled = false;
        retainedBytes = 0;
    }

    /** An observed conflict must never silently select either resource. */
    public static final class Ambiguous extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private Ambiguous() {
            super("Ambiguous resource identity");
        }
    }

    private void expire(int limit) {
        long now = clock.getAsLong();
        Iterator<Entry> iterator = entries.values()
            .iterator();
        while (limit-- > 0 && iterator.hasNext()) {
            Entry entry = iterator.next();
            if (now - entry.touched < idleMillis) break;
            iterator.remove();
            release(entry);
        }
    }

    private void release(Entry entry) {
        reverse.remove(entry.identity);
        retainedBytes -= 512L + 8L * entry.bytes.length;
    }

    private static final class Entry {

        private final IAEKey identity;
        private final byte[] bytes;
        private long touched;

        private Entry(IAEKey identity, byte[] bytes, long touched) {
            this.identity = identity;
            this.bytes = bytes.clone();
            this.touched = touched;
        }
    }
}
