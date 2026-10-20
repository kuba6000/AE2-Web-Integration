package pl.kuba6000.ae2webintegration.core.grid;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings.ResourceHistory;

/** Mutable configuration owned by one retained grid identity. */
@SuppressWarnings("SynchronizeOnNonFinalField") // Bound to the registry before publication; never rebound while in use.
public final class GridSettingsData {

    /**
     * Whether this grid records crafting activity.
     * 
     * @example true
     */
    private boolean isTracked;
    /** Whether new machine-origin jobs are admitted while crafting tracking is enabled. */
    private boolean trackMachineCrafting;
    /** Whether this grid records sampled resource quantities. */
    private boolean isStorageTracked;
    /** Whether this grid records accepted resource input/output history. */
    private boolean isFlowTracked;
    /** Seconds between durable stock samples, independent of terminal refreshes. */
    private int stockSampleIntervalSeconds;
    private int flowFlushIntervalSeconds;
    private int historyRetentionDays;
    private transient @NotNull ResourceHistory policy;
    /**
     * Custom network name; empty when unnamed.
     *
     * @example Factory
     */
    private @NotNull String name = "";
    private transient @NotNull Object lock = this;
    private transient @NotNull Runnable markDirty = () -> {};

    public GridSettingsData() {
        this(new ConfigSettings.ResourceHistory());
    }

    public GridSettingsData(@NotNull ResourceHistory policy) {
        this.policy = policy;
        stockSampleIntervalSeconds = policy.stockSampleIntervalSeconds.defaultValue;
        flowFlushIntervalSeconds = policy.flowFlushIntervalSeconds.defaultValue;
        historyRetentionDays = policy.historyRetentionDays.defaultValue;
    }

    /** Detached settings retained for a retired identity awaiting its final database write. */
    public @NotNull GridSettingsData copy() {
        synchronized (lock) {
            GridSettingsData result = new GridSettingsData(policy);
            result.isTracked = isTracked;
            result.trackMachineCrafting = trackMachineCrafting;
            result.isStorageTracked = isStorageTracked;
            result.isFlowTracked = isFlowTracked;
            result.stockSampleIntervalSeconds = stockSampleIntervalSeconds;
            result.flowFlushIntervalSeconds = flowFlushIntervalSeconds;
            result.historyRetentionDays = historyRetentionDays;
            result.name = name;
            return result;
        }
    }

    /** Normalize retained values under the settings lock when administrator bounds change. */
    public boolean applyPolicy(@NotNull ResourceHistory policy) {
        synchronized (lock) {
            this.policy = policy;
            int stock = policy.stockSampleIntervalSeconds.clamp(stockSampleIntervalSeconds);
            int flow = policy.flowFlushIntervalSeconds.clamp(flowFlushIntervalSeconds);
            int retention = policy.historyRetentionDays.clamp(historyRetentionDays);
            boolean changed = stock != stockSampleIntervalSeconds || flow != flowFlushIntervalSeconds
                || retention != historyRetentionDays;
            stockSampleIntervalSeconds = stock;
            flowFlushIntervalSeconds = flow;
            historyRetentionDays = retention;
            return changed;
        }
    }

    /** Bound before publication; getters, edits and persistence then share the registry monitor. */
    @SuppressWarnings("ConstantValue") // Gson can load an explicit null into a required field.
    void attach(@NotNull Object lock, @NotNull Runnable markDirty) {
        if (name == null) throw new IllegalArgumentException("Missing grid name");
        policy.stockSampleIntervalSeconds.validate(stockSampleIntervalSeconds);
        policy.flowFlushIntervalSeconds.validate(flowFlushIntervalSeconds);
        policy.historyRetentionDays.validate(historyRetentionDays);
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

    public boolean isTrackMachineCrafting() {
        synchronized (lock) {
            return trackMachineCrafting;
        }
    }

    public void setTrackMachineCrafting(boolean value) {
        synchronized (lock) {
            if (trackMachineCrafting == value) return;
            trackMachineCrafting = value;
            markDirty.run();
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
        synchronized (lock) {
            policy.stockSampleIntervalSeconds.validate(value);
            if (stockSampleIntervalSeconds == value) return;
            stockSampleIntervalSeconds = value;
            markDirty.run();
        }
    }

    public int getFlowFlushIntervalSeconds() {
        synchronized (lock) {
            return flowFlushIntervalSeconds;
        }
    }

    public void setFlowFlushIntervalSeconds(int value) {
        synchronized (lock) {
            policy.flowFlushIntervalSeconds.validate(value);
            if (flowFlushIntervalSeconds == value) return;
            flowFlushIntervalSeconds = value;
            markDirty.run();
        }
    }

    public int getHistoryRetentionDays() {
        synchronized (lock) {
            return historyRetentionDays;
        }
    }

    public void setHistoryRetentionDays(int value) {
        synchronized (lock) {
            policy.historyRetentionDays.validate(value);
            if (historyRetentionDays == value) return;
            historyRetentionDays = value;
            markDirty.run();
        }
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
            return !isTracked && !trackMachineCrafting
                && !isStorageTracked
                && !isFlowTracked
                && stockSampleIntervalSeconds == policy.stockSampleIntervalSeconds.defaultValue
                && flowFlushIntervalSeconds == policy.flowFlushIntervalSeconds.defaultValue
                && historyRetentionDays == policy.historyRetentionDays.defaultValue
                && name.isEmpty();
        }
    }

}
