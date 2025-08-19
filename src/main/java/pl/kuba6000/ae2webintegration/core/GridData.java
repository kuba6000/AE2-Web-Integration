package pl.kuba6000.ae2webintegration.core;

import static pl.kuba6000.ae2webintegration.core.AE2WebIntegration.LOG;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import pl.kuba6000.ae2webintegration.core.api.AEApi.AEControllerState;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEPathingGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAESecurityGrid;
import pl.kuba6000.ae2webintegration.core.utils.GSONUtils;

public class GridData {

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
    public HashMap<Integer, Future<IAECraftingJob>> jobs = new HashMap<>();

    public int addJob(Future<IAECraftingJob> job) {
        int jobID = getNextJobID();
        jobs.put(jobID, job);
        return jobID;
    }

    public static GridData get(long gridKey) {
        return gridDataMap.computeIfAbsent(gridKey, k -> new GridData());
    }

    public static GridData get(IAEGrid grid) {
        IAEPathingGrid pathing = grid.web$getPathingGrid();
        if (pathing == null || pathing.web$isNetworkBooting()
            || pathing.web$getControllerState() != AEControllerState.CONTROLLER_ONLINE) {
            return null;
        }
        IAESecurityGrid security = grid.web$getSecurityGrid();
        if (security == null || !security.web$isAvailable()) {
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
            LOG.error("Failed to load web data from file: {}", dataFile.getAbsolutePath(), e);
            gridDataMap.clear();
            saveChanges();
        } finally {
            if (reader != null) try {
                reader.close();
            } catch (Exception ignored) {}
        }

    }
}
