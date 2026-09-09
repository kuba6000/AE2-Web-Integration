package pl.kuba6000.ae2webintegration.core.notification.message;

public class CraftingMessage implements IMessage {

    private final long grid;
    private final String cpuName;
    private final String outputItemName;
    private final long outputItemAmount;
    private final String durationString;
    private final boolean wasCancelled;

    public CraftingMessage(long grid, String cpuName, String outputItemName, long outputItemAmount,
        String durationString, boolean wasCancelled) {
        this.grid = grid;
        this.cpuName = cpuName;
        this.outputItemName = outputItemName;
        this.outputItemAmount = outputItemAmount;
        this.durationString = durationString;
        this.wasCancelled = wasCancelled;
    }

    public long getGrid() {
        return grid;
    }

    public String getCpuName() {
        return cpuName;
    }

    public String getOutputItemName() {
        return outputItemName;
    }

    public long getOutputItemAmount() {
        return outputItemAmount;
    }

    public String getDurationString() {
        return durationString;
    }

    public boolean isWasCancelled() {
        return wasCancelled;
    }

}
