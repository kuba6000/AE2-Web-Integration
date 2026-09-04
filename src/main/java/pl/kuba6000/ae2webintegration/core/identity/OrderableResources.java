package pl.kuba6000.ae2webintegration.core.identity;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

/** Server-thread-only view of the native recipe and emitter collections, without copying their keys. */
public final class OrderableResources {

    private final Supplier<? extends Iterator<? extends IAEKey>> recipes;
    private final Supplier<? extends Iterator<? extends IAEKey>> emitters;
    private final Predicate<IAEKey> membership;
    private final BooleanSupplier nativeReady;
    private long revision;
    private int updateDepth;

    public OrderableResources(Supplier<? extends Iterator<? extends IAEKey>> recipes,
        Supplier<? extends Iterator<? extends IAEKey>> emitters, Predicate<IAEKey> membership,
        BooleanSupplier nativeReady) {
        this.recipes = Objects.requireNonNull(recipes, "recipes");
        this.emitters = Objects.requireNonNull(emitters, "emitters");
        this.membership = Objects.requireNonNull(membership, "membership");
        this.nativeReady = Objects.requireNonNull(nativeReady, "nativeReady");
    }

    public long getRevision() {
        return revision;
    }

    public boolean isReady() {
        return updateDepth == 0 && nativeReady.getAsBoolean() && updateDepth == 0;
    }

    public boolean isCurrent(long expectedRevision) {
        return isReady() && expectedRevision == revision;
    }

    /** Called before a native mutation; unmatched completion keeps the source unavailable after failure. */
    public void beginUpdate() {
        revision++;
        updateDepth++;
    }

    /** Called only after normal completion of the corresponding native mutation. */
    public void endUpdate() {
        if (updateDepth == 0) throw new IllegalStateException("No orderable resource update is active");
        updateDepth--;
    }

    public boolean contains(IAEKey key) {
        long expected = revision;
        requireCurrent(expected);
        boolean result = membership.test(key);
        requireCurrent(expected);
        return result;
    }

    private void requireCurrent(long expected) {
        if (!isCurrent(expected)) throw new UnavailableException();
    }

    /** A deferred, in-progress or invalidated native view cannot resolve an order or publish an index. */
    public static final class UnavailableException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private UnavailableException() {
            super("Orderable resources are not current");
        }
    }

    /** Duplicates are yielded individually so native iteration never hides an unbounded skip loop. */
    public Iterator<IAEKey> openCursor() {
        long expected = revision;
        requireCurrent(expected);
        Iterator<? extends IAEKey> first = recipes.get();
        requireCurrent(expected);
        return new Iterator<IAEKey>() {

            private Iterator<? extends IAEKey> iterator = first;
            private boolean inEmitters;

            @Override
            public boolean hasNext() {
                requireCurrent(expected);
                boolean available = iterator.hasNext();
                requireCurrent(expected);
                if (available) return true;
                if (!inEmitters) {
                    iterator = emitters.get();
                    requireCurrent(expected);
                    inEmitters = true;
                    available = iterator.hasNext();
                    requireCurrent(expected);
                }
                return available;
            }

            @Override
            public IAEKey next() {
                if (!hasNext()) throw new NoSuchElementException();
                IAEKey key = iterator.next();
                requireCurrent(expected);
                return key;
            }
        };
    }
}
