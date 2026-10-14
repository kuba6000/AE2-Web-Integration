package pl.kuba6000.ae2webintegration.core.grid;

import org.jetbrains.annotations.NotNull;

/** Mutable configuration owned by one retained grid identity. */
@SuppressWarnings("SynchronizeOnNonFinalField") // Bound to the registry before publication; never rebound while in use.
public final class GridSettingsData {

    public static final int DEFAULT_STOCK_SAMPLE_INTERVAL_SECONDS = 300;
    public static final int MAX_STOCK_SAMPLE_INTERVAL_SECONDS = 86400;

    /**
     * Whether this grid records crafting activity.
     * 
     * @example true
     */
    private boolean isTracked;
    /** Whether this grid records sampled resource quantities. */
    private boolean isStorageTracked;
    /** Whether this grid records accepted resource input/output history. */
    private boolean isFlowTracked;
    /** Seconds between durable stock samples, independent of terminal refreshes. */
    private int stockSampleIntervalSeconds = DEFAULT_STOCK_SAMPLE_INTERVAL_SECONDS;
    /**
     * Custom network name; empty when unnamed.
     *
     * @example Factory
     */
    private @NotNull String name = "";
    private transient @NotNull Object lock = this;
    private transient @NotNull Runnable markDirty = () -> {};

    /** Bound before publication; getters, edits and persistence then share the registry monitor. */
    @SuppressWarnings("ConstantValue") // Gson can load an explicit null into a required field.
    void attach(@NotNull Object lock, @NotNull Runnable markDirty) {
        if (name == null) throw new IllegalArgumentException("Missing grid name");
        validateStockInterval(stockSampleIntervalSeconds);
        this.lock = lock;
        this.markDirty = markDirty;
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
            markDirty.run();
        }
    }

    public boolean isStorageTracked() {
        synchronized (lock) {
            return isStorageTracked;
        }
    }

    public void setStorageTracked(boolean value) {
        synchronized (lock) {
            if (isStorageTracked == value) return;
            isStorageTracked = value;
            markDirty.run();
        }
    }

    public boolean isFlowTracked() {
        synchronized (lock) {
            return isFlowTracked;
        }
    }

    public void setFlowTracked(boolean value) {
        synchronized (lock) {
            if (isFlowTracked == value) return;
            isFlowTracked = value;
            markDirty.run();
        }
    }

    public int getStockSampleIntervalSeconds() {
        synchronized (lock) {
            return stockSampleIntervalSeconds;
        }
    }

    public void setStockSampleIntervalSeconds(int value) {
        validateStockInterval(value);
        synchronized (lock) {
            if (stockSampleIntervalSeconds == value) return;
            stockSampleIntervalSeconds = value;
            markDirty.run();
        }
    }

    private static void validateStockInterval(int value) {
        if (value < 1 || value > MAX_STOCK_SAMPLE_INTERVAL_SECONDS) throw new IllegalArgumentException(
            "Stock interval must be 1.." + MAX_STOCK_SAMPLE_INTERVAL_SECONDS + " seconds");
    }

    public @NotNull String getName() {
        synchronized (lock) {
            return name;
        }
    }

    public void setName(@NotNull String value) {
        synchronized (lock) {
            if (name.equals(value)) return;
            name = value;
            markDirty.run();
        }
    }

    public boolean isDefault() {
        synchronized (lock) {
            return !isTracked && !isStorageTracked
                && !isFlowTracked
                && stockSampleIntervalSeconds == DEFAULT_STOCK_SAMPLE_INTERVAL_SECONDS
                && name.isEmpty();
        }
    }

}
