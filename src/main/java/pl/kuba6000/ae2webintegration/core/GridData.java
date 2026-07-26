package pl.kuba6000.ae2webintegration.core;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

public class GridData {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    @GSONUtils.SkipGSON
    private static final File dataFile = Config.getConfigFile("griddata.json");

    @GSONUtils.SkipGSON
    private static ConcurrentHashMap<Long, GridData> gridDataMap = new ConcurrentHashMap<>();

    public boolean isTracked = false;

    @GSONUtils.SkipGSON
    public AE2JobTracker trackingInfo = new AE2JobTracker();

    @GSONUtils.SkipGSON
    private int nextJobID = 1;

    private int getNextJobID() {
        return nextJobID++;
    }

    @GSONUtils.SkipGSON
    public ConcurrentHashMap<Integer, Future<IAECraftingJob>> jobs = new ConcurrentHashMap<>();

    public int addJob(Future<IAECraftingJob> job) {
        int jobID = getNextJobID();
        jobs.put(jobID, job);
        return jobID;
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
        Gson gson = GSONUtils.GSON_BUILDER.create();
        Writer writer = null;
        try {
            writer = Files.newWriter(dataFile, StandardCharsets.UTF_8);
            gson.toJson(gridDataMap, writer);
            writer.flush();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (writer != null) try {
                writer.close();
            } catch (Exception ignored) {}
        }
    }

    public static void loadData() {
        Gson gson = GSONUtils.GSON_BUILDER.create();
        if (!dataFile.exists()) {
            LOG.info("Grid data file not found, creating a new one.");
            saveChanges();
            return;
        }
        Reader reader = null;
        try {
            reader = Files.newReader(dataFile, StandardCharsets.UTF_8);
            Type type = new TypeToken<ConcurrentHashMap<Long, GridData>>() {}.getType();
            gridDataMap = gson.fromJson(reader, type);
        } catch (Exception e) {
            LOG.error("Failed to load web data from file: " + dataFile.getAbsolutePath(), e);
            gridDataMap.clear();
            saveChanges();
        } finally {
            if (reader != null) try {
                reader.close();
            } catch (Exception ignored) {}
        }

    }
}
