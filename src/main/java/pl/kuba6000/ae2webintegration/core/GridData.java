package pl.kuba6000.ae2webintegration.core;

import java.io.File;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

public class GridData {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    /** Resolved per call - see the same note on CoreData. */
    private static File dataFile() {
        return Config.getConfigFile("griddata.json");
    }

    @GSONUtils.SkipGSON
    private static ConcurrentHashMap<Long, GridData> gridDataMap = new ConcurrentHashMap<>();

    @GSONUtils.SkipGSON
    private static Iterator<GridData> craftingPlanMaintenanceCursor;

    public boolean isTracked = false;

    @GSONUtils.SkipGSON
    public AE2JobTracker trackingInfo = new AE2JobTracker();

    @GSONUtils.SkipGSON
    private CraftingPlanRegistry craftingPlans = new CraftingPlanRegistry(System::nanoTime);

    public int addJob(Future<IAECraftingJob> job) {
        return craftingPlans.add(job);
    }

    public Future<IAECraftingJob> getJob(int jobId) {
        return craftingPlans.find(jobId);
    }

    public boolean removeJob(int jobId) {
        return craftingPlans.remove(jobId);
    }

    public boolean cancelJob(int jobId) {
        return craftingPlans.cancel(jobId);
    }

    public static synchronized void clearRuntimeState() {
        for (GridData gridData : gridDataMap.values()) {
            gridData.craftingPlans.clearForServerStop();
            gridData.trackingInfo.clearHistory();
        }
        craftingPlanMaintenanceCursor = null;
    }

    static synchronized boolean evictExpiredCompletedPlans(long nowNanos, int maxGrids) {
        if (craftingPlanMaintenanceCursor == null) {
            craftingPlanMaintenanceCursor = gridDataMap.values()
                .iterator();
        }

        int processed = 0;
        while (processed < maxGrids && craftingPlanMaintenanceCursor.hasNext()) {
            craftingPlanMaintenanceCursor.next().craftingPlans.evictExpiredCompleted(nowNanos);
            processed++;
        }

        if (craftingPlanMaintenanceCursor.hasNext()) {
            return false;
        }
        craftingPlanMaintenanceCursor = null;
        return true;
    }

    /**
     * Looks up stored settings without creating them. {@code null} means this grid has none yet, which is
     * normal - a grid only gets an entry once something is actually stored for it.
     */
    public static GridData find(long gridKey) {
        return gridDataMap.get(gridKey);
    }

    /**
     * Creates the entry if it is missing, so only call this when there is something to store. Callers must
     * have established that the key belongs to a real grid: entries are persisted to griddata.json, so
     * creating one for an arbitrary key writes a phantom grid to disk.
     */
    public static GridData getOrCreate(long gridKey) {
        return gridDataMap.computeIfAbsent(gridKey, k -> new GridData());
    }

    public static GridData getOrCreate(IAEGrid grid) {
        IAESecurityGrid security = GridFilter.usableSecurity(grid);
        if (security == null) {
            return null;
        }
        long gridKey = security.web$getSecurityKey();
        if (gridKey == -1) {
            return null;
        }
        return gridDataMap.computeIfAbsent(gridKey, k -> new GridData());
    }

    public static void saveChanges() {
        try {
            GSONUtils.writeAtomically(dataFile(), gridDataMap);
        } catch (Exception e) {
            LOG.error("Failed to save grid data", e);
        }
    }

    public static void loadData() {
        Gson gson = GSONUtils.GSON_BUILDER.create();
        File file = dataFile();
        if (!file.exists()) {
            LOG.info("Grid data file not found, creating a new one.");
            saveChanges();
            return;
        }
        try (Reader reader = Files.newReader(file, StandardCharsets.UTF_8)) {
            Type type = new TypeToken<ConcurrentHashMap<Long, GridData>>() {}.getType();
            ConcurrentHashMap<Long, GridData> loaded = gson.fromJson(reader, type);
            if (loaded == null) {
                LOG.error("Grid data file is empty or malformed, keeping the settings already in memory");
                return;
            }
            gridDataMap = loaded;
        } catch (Exception e) {
            // As in CoreData: a failed read must not overwrite the file it failed on.
            LOG.error("Failed to load grid data from file: " + file.getAbsolutePath(), e);
        }
    }
}
