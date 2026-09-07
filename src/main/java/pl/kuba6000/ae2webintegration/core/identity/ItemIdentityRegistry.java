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

    public void clear() {
        entries.clear();
        reverse.clear();
        ambiguous.clear();
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
