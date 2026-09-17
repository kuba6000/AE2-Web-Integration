package pl.kuba6000.ae2webintegration.core.http.endpoint.cpu;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.api.JSON_CompactedItem;
import pl.kuba6000.ae2webintegration.core.api.JSON_Stack;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.IStackList;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.tracking.AE2JobTracker;

/**
 * Reads a crafting CPU and its current work.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @pathParam cpuKey Stable crafting CPU identifier.
 * @response 200 {@link Response} Successful response.
 * @response 400 {@link ErrorResponse} BAD_PARAM: malformed path value, unexpected body, or invalid JSON/input
 *           fields.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: no valid session was provided; response includes
 *           WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: the user cannot access this grid.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: the grid does not exist. CPU_NOT_FOUND: no CPU has this key.
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
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/cpus/{cpuKey}")
public final class GetCPU extends ISyncedRequest {

    /**
     * Successful operation result.
     * 
     * @param status {@code OK} for a successful request
     * @param data   current CPU capacity, work and available tracking measurements
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull ClusterData data) {}

    @SuppressWarnings("unused") // Gson reads the fields reflectively.
    public static class ClusterData {

        /**
         * Total storage capacity of this CPU in bytes.
         *
         * @example 16384
         */
        public long size;
        /**
         * Whether the CPU is currently crafting.
         *
         * @example true
         */
        public boolean isBusy;
        /** Detached final output snapshot; null when the CPU is idle or its output is unavailable. */
        public @Nullable JSON_Stack finalOutput;
        /** Resource details for current work; null when the CPU is idle. */
        public @Nullable ArrayList<JSON_CompactedItem> items;
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
        /**
         * Elapsed crafting time in milliseconds; zero when tracking is unavailable.
         *
         * @example 10000
         */
        public long timeElapsed = 0L;
    }

    @PathParam("cpuKey")
    @SuppressWarnings("NotNullFieldNotInitialized") // Bound before the request is submitted.
    private @NotNull StableKey cpuId;

    @Override
    protected void handle(IAEGrid grid) {
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();

        Map<StableKey, ICraftingCPUCluster> cpus = GetCPUList.getCPUList(craftingGrid);
        ICraftingCPUCluster cpu = cpus.get(cpuId);
        if (cpu == null) {
            deny(ApiStatus.CPU_NOT_FOUND);
            return;
        }

        ClusterData clusterData = new ClusterData();
        clusterData.size = cpu.web$getAvailableStorage();
        clusterData.isBusy = cpu.web$isBusy();
        if (clusterData.isBusy) {
            clusterData.finalOutput = JSON_Stack.capture(grid, cpu.web$getFinalOutput());
            AE2JobTracker.JobTrackingInfo trackingInfo = AE2JobTracker.findActiveJob(cpu);
            clusterData.hasTrackingInfo = trackingInfo != null;

            Map<IAEKey, JSON_CompactedItem> prep = new HashMap<>();
            IStackList allItems = AE2Controller.AE2Interface.web$createStackList();
            cpu.web$getAllItems(allItems);
            for (IAEGenericStack stack : allItems.web$stacks()) {
                IAEKey key = stack.web$what();
                JSON_CompactedItem compactedItem = prep.computeIfAbsent(key, JSON_CompactedItem::new);
                compactedItem.active += cpu.web$getActiveItems(key);
                compactedItem.pending += cpu.web$getPendingItems(key);
                compactedItem.stored += cpu.web$getStorageItems(key);
            }

            if (clusterData.hasTrackingInfo) {
                clusterData.timeStarted = trackingInfo.timeStarted;
                clusterData.timeElapsed = (System.currentTimeMillis()) - clusterData.timeStarted;
                for (IAEKey key : trackingInfo.timeSpentOn.keySet()) {
                    JSON_CompactedItem compactedItem = prep.computeIfAbsent(key, JSON_CompactedItem::new);
                    compactedItem.timeSpentCrafting += trackingInfo.getTimeSpentOn(key);
                    compactedItem.craftedTotal += trackingInfo.craftedTotal.getOrDefault(key, 0L);
                    compactedItem.shareInCraftingTime += trackingInfo.getShareInCraftingTime(key);
                    compactedItem.shareInCraftingTimeCombined = clusterData.timeElapsed > 0
                        ? Math.min(((double) compactedItem.timeSpentCrafting) / (double) clusterData.timeElapsed, 1d)
                        : 0d;
                    compactedItem.craftsPerSec = compactedItem.timeSpentCrafting > 0
                        ? (double) compactedItem.craftedTotal
                            / (compactedItem.timeSpentCrafting / (double) TimeUnit.SECONDS.toMillis(1))
                        : 0d;
                }
            }

            clusterData.items = new ArrayList<>(prep.values());
            clusterData.items.sort((i1, i2) -> {
                if (i1.active > 0 && i2.active > 0) return Long.compare(i2.active, i1.active);
                else if (i1.active > 0 && i2.active == 0) return -1;
                else if (i1.active == 0 && i2.active > 0) return 1;
                if (i1.pending > 0 && i2.pending > 0) return Long.compare(i2.pending, i1.pending);
                else if (i1.pending > 0 && i2.pending == 0) return -1;
                else if (i1.pending == 0 && i2.pending > 0) return 1;
                return Long.compare(i2.stored, i1.stored);
            });

        }

        respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, clusterData));
    }

}
