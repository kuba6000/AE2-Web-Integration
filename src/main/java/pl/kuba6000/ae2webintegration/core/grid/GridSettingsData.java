package pl.kuba6000.ae2webintegration.core.grid;

import org.jetbrains.annotations.NotNull;

/** Mutable configuration owned by one retained grid identity. */
@SuppressWarnings("SynchronizeOnNonFinalField") // Bound to the registry before publication; never rebound while in use.
public final class GridSettingsData {

    private boolean isTracked;
    private transient @NotNull Object lock = this;
    private transient @NotNull Runnable onChange = () -> {};

    /** Bound before publication; getters, edits and persistence then share the registry monitor. */
    void attach(@NotNull Object lock, @NotNull Runnable onChange) {
        this.lock = lock;
        this.onChange = onChange;
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
            onChange.run();
        }
    }

    public boolean isDefault() {
        synchronized (lock) {
            return !isTracked;
        }
    }

}
