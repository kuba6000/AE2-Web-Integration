package pl.kuba6000.ae2webintegration.core.http.endpoint.crafting;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.dto.ResourceDescription;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEMeInventoryItem;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummary;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingPlanSummaryEntry;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAEStorageGrid;

/**
 * Reads the calculation state and contents of a crafting plan.
 *
 * @pathParam gridKey Persistent grid identifier.
 * @pathParam planId Runtime crafting-plan identifier.
 * @response 200 {@link Response} Successful response.
 * @response 400 {@link ErrorResponse} BAD_PARAM: malformed path value, unexpected body, or invalid JSON/input
 *           fields.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: no valid session was provided; response includes
 *           WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: the user cannot access this grid.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND or INVALID_ID: the grid or plan does not exist.
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
@Endpoint(method = HttpMethod.GET, path = "/api/grids/{gridKey}/crafting-plans/{planId}")
public final class GetCraftingPlan extends ISyncedRequest {

    private static final Logger LOG = LogManager.getLogger("ae2webintegration");

    /** Include resource icon references and atlas metadata; omitted means false. */
    @QueryParam("icons")
    private boolean icons;

    @SuppressWarnings("unused") // Gson reads the fields reflectively.
    public static class PlanData {

        /**
         * Whether calculation has finished.
         *
         * @example true
         */
        public boolean isDone;
        /**
         * Whether the completed plan is a simulation with missing resources; false while calculation is pending.
         *
         * @example false
         */
        public boolean isSimulating;
        /**
         * Crafting storage required by the calculated plan, in bytes; zero while calculation is pending.
         *
         * @example 4096
         */
        public long bytesTotal;
        /** Calculated resource rows; null while calculation is pending. */
        public @Nullable ArrayList<JobItem> plan;

        /** One resource required by the calculated crafting plan. */
        @SuppressWarnings("unused") // Gson reads the fields reflectively.
        public static final class JobItem extends ResourceDescription {

            /**
             * Resource units taken from network storage.
             *
             * @example 0
             */
            public final long stored;
            /**
             * Resource units to be crafted.
             *
             * @example 64
             */
            public final long requested;
            /**
             * Resource units missing from a simulation.
             *
             * @example 0
             */
            public final long missing;
            /**
             * Number of crafting steps; zero when the platform cannot report it.
             *
             * @example 64
             */
            public final long steps;
            /**
             * Fraction of currently available stored units consumed by a storage-only row; zero when the row requests
             * crafting, has missing units or has no available storage.
             *
             * @example 0.0
             */
            public final double usedPercent;

            public JobItem(@Nullable String registryNamespace, @Nullable String registryPath,
                @NotNull String displayName, int componentCount, int damage, long stored, long requested, long missing,
                long steps, double usedPercent, @Nullable StableKey itemKey, @Nullable IconMappings.Reference icon) {
                super(registryNamespace, registryPath, displayName, componentCount, damage, itemKey, icon);
                this.stored = stored;
                this.requested = requested;
                this.missing = missing;
                this.steps = steps;
                this.usedPercent = usedPercent;
            }
        }

    }

    /**
     * Successful operation result.
     * 
     * @param status {@code OK} for a successful request
     * @param data   calculation state and, once complete, the required storage and resource plan
     * @param icons  atlas metadata; null when not requested or no pack is available
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull PlanData data, @Nullable IconMappings icons) {}

    @PathParam("planId")
    private int jobId;

    @Override
    protected void handle(IAEGrid grid) {
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        Future<IAECraftingJob> job = gridData.getJob(jobId);
        if (job == null) {
            deny(ApiStatus.INVALID_ID);
            return;
        }
        PlanData jobData = new PlanData();
        IconPack pack = icons ? CoreEngine.getIconPack() : null;
        IconMappings mappings = pack == null ? null : new IconMappings(pack);
        jobData.isDone = job.isDone();
        if (jobData.isDone) {
            try {
                IAECraftingJob craftingJob = job.get();
                IAEStorageGrid storageGrid = grid.web$getStorageGrid();
                IAEMeInventoryItem inventory = storageGrid.web$getInventory();
                jobData.isSimulating = craftingJob.web$isSimulation();
                jobData.bytesTotal = craftingJob.web$getByteTotal();
                ICraftingPlanSummary summary = craftingJob.web$generateSummary(grid);
                jobData.plan = new ArrayList<>();
                ItemIdentityRegistry.Listing identities = AE2Controller.itemIdentities.beginListing(craftingJob);
                for (ICraftingPlanSummaryEntry entry : summary.web$getEntries()) {
                    IAEKey key = entry.web$getWhat();
                    String registryPath = key.web$getRegistryPath();
                    String displayName = key.web$getDisplayName();
                    long requested = entry.web$getCraftAmount();
                    long steps = entry.web$getCraftSteps();
                    long stored = entry.web$getStoredAmount();
                    long missing = entry.web$getMissingAmount();
                    double usedPercent = 0d;
                    StableKey itemKey = null;
                    IconMappings.Reference icon = null;
                    try {
                        itemKey = identities.remember(key);
                        if (mappings != null) icon = mappings.resolve(itemKey, AE2Controller.itemIdentities);
                    } catch (RuntimeException unavailable) {
                        // Optional identity or icon failures must not hide the calculated resource counts.
                    }
                    if (missing == 0 && requested == 0 && stored > 0) {
                        long available = inventory.web$getAvailable(key, grid);
                        if (available > 0L) {
                            usedPercent = (double) stored / (double) available;
                        }
                    }
                    jobData.plan.add(
                        new PlanData.JobItem(
                            key.web$getRegistryNamespace(),
                            registryPath,
                            displayName,
                            key.web$getComponentCount(),
                            key.web$getDamage(),
                            stored,
                            requested,
                            missing,
                            steps,
                            usedPercent,
                            itemKey,
                            icon));
                }
                identities.commit();
                jobData.plan.sort((i1, i2) -> {
                    if (i1.missing > 0 && i2.missing > 0) return Long.compare(i2.missing, i1.missing);
                    else if (i1.missing > 0 && i2.missing == 0) return -1;
                    else if (i1.missing == 0 && i2.missing > 0) return 1;
                    if (i1.requested > 0 && i2.requested > 0) return Long.compare(i2.steps, i1.steps);
                    else if (i1.requested > 0 && i2.requested == 0) return -1;
                    else if (i1.requested == 0 && i2.requested > 0) return 1;
                    return Long.compare(i2.stored, i1.stored);
                });
            } catch (InterruptedException | ExecutionException e) {
                LOG.error("Failed to read crafting job", e);
                deny(ApiStatus.INTERNAL_ERROR);
                return;
            }
        }
        respond(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, jobData, mappings));
    }
}
