package pl.kuba6000.ae2webintegration.core.grid;

import org.jetbrains.annotations.NotNull;

/** Mutable configuration owned by one retained grid identity. */
@SuppressWarnings("SynchronizeOnNonFinalField") // Bound to the registry before publication; never rebound while in use.
public final class GridSettingsData {

    private boolean isTracked;
    private transient boolean dirty;
    private transient @NotNull Object lock = this;

    /** Bound before publication; getters, edits and persistence then share the registry monitor. */
    void attachLock(@NotNull Object lock) {
        this.lock = lock;
    }

    public boolean isTracked() {
        synchronized (lock) {
            return isTracked;
        }
    }

    public void setTracked(boolean value) {
        synchronized (lock) {
            if (isTracked == value) return;
            isTracked = value;
            dirty = true;
        }
    }

    public boolean isDefault() {
        synchronized (lock) {
            return !isTracked;
        }
    }

    public boolean isDirty() {
        synchronized (lock) {
            return dirty;
        }
    }

    /** Called by persistence only after this object's values have been written successfully. */
    public void markSaved() {
        synchronized (lock) {
            dirty = false;
        }
    }
}
