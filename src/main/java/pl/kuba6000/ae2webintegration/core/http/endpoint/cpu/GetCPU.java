package pl.kuba6000.ae2webintegration.core.http.endpoint.cpu;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.api.CpuSelectionMode;
import pl.kuba6000.ae2webintegration.core.cpu.CpuLiveCoordinator;
import pl.kuba6000.ae2webintegration.core.cpu.CpuObservation;
import pl.kuba6000.ae2webintegration.core.cpu.CpuReader;
import pl.kuba6000.ae2webintegration.core.cpu.CpuRegistry;
import pl.kuba6000.ae2webintegration.core.cpu.CpuTopic;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.dto.CpuResource;
import pl.kuba6000.ae2webintegration.core.http.dto.ResourceStack;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.live.LiveSnapshotRequest;
import pl.kuba6000.ae2webintegration.core.live.LiveView;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/**
 * Reads a crafting CPU and its current work.
 * With live=true the response also carries a view and a single-use subscription token for the live stream at
 * cpus/{cpuKey}/events.
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
public final class GetCPU extends LiveSnapshotRequest {

    /**
     * Successful operation result.
     * 
     * @param status       {@code OK} for a successful request
     * @param data         current CPU capacity, work and available tracking measurements
     * @param view         identifies the state a live snapshot holds; null unless live was requested
     * @param subscription single-use token for the live stream; null unless live was requested
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull ClusterData data, @Nullable IconMappings icons,
        @Nullable LiveView view, @Nullable String subscription) {

        public Response(ApiStatus status, ClusterData data, IconMappings icons) {
            this(status, data, icons, null, null);
        }
    }

    /** Include icon atlas references and page metadata in the response. */
    @QueryParam("icons")
    private boolean icons;

    @SuppressWarnings("unused") // Gson reads the fields reflectively.
    public static class ClusterData {

        public ClusterData(@NotNull CpuObservation.Header header, long at, @Nullable IconMappings mappings) {
            size = header.size();
            isBusy = header.busy();
            selectionMode = header.selectionMode();
            supportsPause = header.supportsPause();
            isPaused = header.paused();
            finalOutput = header.finalOutput() == null ? null : new ResourceStack(header.finalOutput(), mappings);
            hasTrackingInfo = header.hasTrackingInfo();
            timeStarted = header.timeStarted();
            timeElapsed = header.timeElapsed(at);
        }

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
        /** Native automatic-selection policy; null when unsupported or unknown. Not a submission permission. */
        public @Nullable CpuSelectionMode selectionMode;
        /** Whether this CPU supports pausing the scheduling of its current job. */
        public boolean supportsPause;
        /** Whether the active job is paused; false for idle or unsupported CPUs. */
        public boolean isPaused;
        /** Detached final output snapshot; null when the CPU is idle or its output is unavailable. */
        public @Nullable ResourceStack finalOutput;
        /** Resource details for current work; null when the CPU is idle. */
        public @Nullable ArrayList<CpuResource> items;
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
    private @NotNull StableKey cpuId;

    @Override
    protected void cancel(String token) {
        CpuRegistry registry = CoreEngine.getCpuRegistry();
        if (registry != null) registry.cancel(token);
    }

    @Override
    protected void handle(IAEGrid grid) {
        if (replace != null && replace.length() != 36) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        ICraftingCPUCluster cpu = GetCPUList.getCPUList(grid.web$getCraftingGrid())
            .get(cpuId);
        if (cpu == null) {
            deny(ApiStatus.CPU_NOT_FOUND);
            return;
        }
        IconPack pack = icons ? CoreEngine.getIconPack() : null;
        IconMappings mappings = pack == null ? null : new IconMappings(pack);
        if (live && !context.getExchange()
            .getRequestMethod()
            .equals("HEAD")) {
            CpuLiveCoordinator coordinator = CoreEngine.getCpuLive();
            CpuRegistry registry = CoreEngine.getCpuRegistry();
            if (coordinator == null || registry == null) {
                deny(ApiStatus.SERVER_STOPPING);
                return;
            }
            coordinator.prepareForViewer(gridKey, grid, cpuId, cpu, System.currentTimeMillis());
            CpuRegistry.Bootstrap bootstrap = registry.reserve(
                gridKey,
                cpuId,
                context.getSession(),
                icons,
                pack == null ? null : pack.packId(),
                replace,
                System.currentTimeMillis());
            if (bootstrap == null) {
                deny(ApiStatus.SERVER_BUSY);
                return;
            }
            subscription = bootstrap.subscription();
            respondDetached(
                HttpURLConnection.HTTP_OK,
                new Response(
                    ApiStatus.OK,
                    clusterData(
                        bootstrap.header(),
                        bootstrap.rows(),
                        bootstrap.descriptions(),
                        bootstrap.unkeyed(),
                        bootstrap.at(),
                        mappings),
                    mappings,
                    bootstrap.view(),
                    subscription));
            return;
        }
        long now = System.currentTimeMillis();
        CpuObservation observation = CpuReader.read(grid, cpu, now, mappings != null);
        respond(
            HttpURLConnection.HTTP_OK,
            new Response(
                ApiStatus.OK,
                clusterData(
                    observation.header(),
                    observation.rows(),
                    observation.descriptions(),
                    observation.unkeyed(),
                    now,
                    mappings),
                mappings));
    }

    private static @NotNull ClusterData clusterData(@NotNull CpuObservation.Header header,
        @NotNull List<CpuObservation.Row> rows, @NotNull Map<StableKey, ResourceDescriptor> descriptions,
        @NotNull List<CpuObservation.UnkeyedRow> unkeyed, long at, @Nullable IconMappings mappings) {
        ClusterData data = new ClusterData(header, at, mappings);
        if (header.busy()) data.items = CpuTopic.items(rows, descriptions, unkeyed, header, at, mappings);
        return data;
    }

}
