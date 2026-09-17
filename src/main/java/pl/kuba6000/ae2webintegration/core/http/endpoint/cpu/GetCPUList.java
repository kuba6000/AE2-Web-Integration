package pl.kuba6000.ae2webintegration.core.http.endpoint.cpu;

import java.net.HttpURLConnection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.api.JSON_Stack;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

/**
 * Lists the crafting CPUs on a grid.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @response 200 {@link Response} Successful response.
 * @response 400 {@link ErrorResponse} BAD_PARAM: malformed path value, unexpected body, or invalid JSON/input
 *           fields.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: no valid session was provided; response includes
 *           WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: the user cannot access this grid.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: the grid does not exist or is unavailable.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: this path does not support the method; Allow lists
 *           supported methods.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: the request could not be completed because of an unexpected
 *           failure.
 * @response 503 {@link ErrorResponse} SERVER_BUSY, SERVER_STOPPING or TIMEOUT: the game-thread request could not
 *           complete.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 403 {"status":"NO_PERMISSIONS","data":null}
 * @responseExample 404 {"status":"GRID_NOT_FOUND","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_BUSY","data":null}
 */
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/cpus")
public final class GetCPUList extends ISyncedRequest {

    /**
     * Successful operation result.
     * 
     * @param status {@code OK} for a successful request
     * @param data   CPU summaries keyed by stable CPU identifier; empty when the grid has no crafting CPUs
     * @example status OK
     * @keyExample data AQEBAQEBAQEBAQEBAQEBAQ
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull Map<StableKey, CpuInfo> data) {}

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    @SuppressWarnings("unused") // Gson reads the fields reflectively.
    public static class CpuInfo {

        /**
         * Display name of the crafting CPU.
         *
         * @example Main Crafting CPU
         */
        public String name;
        /**
         * Whether the CPU is currently crafting.
         *
         * @example true
         */
        public boolean isBusy;
        /** Detached final output snapshot; null when the CPU is idle or its output is unavailable. */
        public @Nullable JSON_Stack finalOutput;
        /**
         * Total CPU crafting storage in bytes.
         *
         * @example 16384
         */
        public long availableStorage;
        /**
         * Currently used storage in bytes, or -1 when the platform cannot report it.
         *
         * @example 4096
         */
        public long usedStorage;
        /**
         * Number of crafting coprocessors.
         *
         * @example 4
         */
        public long coProcessors;
        /**
         * Whether measurements are available for the active job.
         *
         * @example true
         */
        public boolean hasTrackingInfo = false;
        /**
         * Crafting start time in Unix epoch milliseconds; zero when unavailable.
         *
         * @example 1700000000000
         */
        public long timeStarted = 0L;
    }

    /** Duplicate addresses are logged; the last CPU for an address is retained. */
    @NotNull
    public static Map<StableKey, ICraftingCPUCluster> getCPUList(@NotNull IAECraftingGrid craftingGrid) {
        LinkedHashMap<StableKey, ICraftingCPUCluster> orderedMap = new LinkedHashMap<>();
        for (ICraftingCPUCluster cpu : craftingGrid.web$getCPUs()) {
            StableKey id = cpu.web$getKey();
            ICraftingCPUCluster previous = orderedMap.put(id, cpu);
            if (previous != null) {
                LOG.error(
                    "Duplicate crafting CPU ID '{}' for '{}' and '{}'; retaining the last CPU",
                    id,
                    previous.web$getName(),
                    cpu.web$getName());
            }
        }
        return orderedMap;
    }

    @Override
    protected void handle(IAEGrid grid) {
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        Map<StableKey, ICraftingCPUCluster> clusters = getCPUList(grid.web$getCraftingGrid());
        LinkedHashMap<StableKey, CpuInfo> cpuList = new LinkedHashMap<>(clusters.size());
        for (Map.Entry<StableKey, ICraftingCPUCluster> entry : clusters.entrySet()) {
            CpuInfo cpuInfo = new CpuInfo();
            ICraftingCPUCluster cluster = entry.getValue();
            cpuInfo.name = cluster.web$getName();
            cpuInfo.availableStorage = cluster.web$getAvailableStorage();
            cpuInfo.usedStorage = cluster.web$getUsedStorage();
            cpuInfo.coProcessors = cluster.web$getCoProcessors();
            cpuInfo.isBusy = cluster.web$isBusy();
            if (cpuInfo.isBusy) {
                cpuInfo.finalOutput = JSON_Stack.capture(grid, cluster.web$getFinalOutput());
                AE2JobTracker.JobTrackingInfo trackingInfo = AE2JobTracker.findActiveJob(cluster);
                if (trackingInfo != null) {
                    cpuInfo.hasTrackingInfo = true;
                    cpuInfo.timeStarted = trackingInfo.timeStarted;
                }
            }
            cpuList.put(entry.getKey(), cpuInfo);
        }
        respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, cpuList));
    }

}
