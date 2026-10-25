package pl.kuba6000.ae2webintegration.core.http.endpoint.crafting;

import static pl.kuba6000.ae2webintegration.core.AE2Controller.itemIdentities;

import java.net.HttpURLConnection;
import java.util.concurrent.Future;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.AE2Controller.RequestContext;
import pl.kuba6000.ae2webintegration.core.ae2request.sync.ISyncedRequest;
import pl.kuba6000.ae2webintegration.core.api.CraftingOptions;
import pl.kuba6000.ae2webintegration.core.api.ServerCapability;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput;
import pl.kuba6000.ae2webintegration.core.identity.ItemIdentityRegistry;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAECraftingJob;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceSnapshot;

/**
 * Create crafting plan.
 *
 * <p>
 * Starts a calculation. Use the returned {@code jobId} to check the plan, then submit it to start crafting.
 *
 * @pathParam gridKey Persistent network identifier.
 * @response 202 {@link Response} Calculation accepted; use jobId to check the plan.
 * @response 400 {@link ErrorResponse} BAD_PARAM: invalid request or unsupported calculation option.
 *           INVALID_QUANTITY: quantity must be positive.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required. Includes WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: access to this network denied. CSRF_REJECTED: cookie or
 *           configured-local mutations require X-AE2-Request: true.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND, ITEM_IDENTITY_UNKNOWN or ITEM_NOT_FOUND: the network or
 *           craftable resource does not exist.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method. Allow lists supported methods.
 * @response 409 {@link ErrorResponse} AMBIGUOUS_ITEM_KEY: the key matches more than one resource.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 415 {@link ErrorResponse} UNSUPPORTED_MEDIA_TYPE: a nonempty request body requires Content-Type:
 *           application/json.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: unexpected server error.
 * @response 503 {@link ErrorResponse} SERVER_BUSY, SERVER_STOPPING or TIMEOUT: server unavailable or request timed out.
 * @responseExample 400 {"status":"BAD_PARAM","data":null}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 403 {"status":"NO_PERMISSIONS","data":null}
 * @responseExample 404 {"status":"GRID_NOT_FOUND","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 409 {"status":"AMBIGUOUS_ITEM_KEY","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 415 {"status":"UNSUPPORTED_MEDIA_TYPE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"SERVER_BUSY","data":null}
 */
@Endpoint(method = HttpMethod.POST, path = "/api/grids/{gridKey}/crafting-plans")
public final class CreateCraftingPlan extends ISyncedRequest {

    /**
     * @param status {@code OK} for a successful request
     * @param data   identifier of the accepted calculation; use it to poll, submit or delete the plan
     * @example status OK
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull PlanCreated data) implements IResponseBody {}

    /**
     * Accepted crafting calculation.
     * 
     * @param jobId plan identifier for this network; valid until the plan is removed or the server restarts
     * @example jobId 7
     */
    @Desugar
    public record PlanCreated(int jobId) {}

    /**
     * Parameters for a new crafting plan.
     *
     * @param itemKey       stable resource identity returned by the item listing
     * @param quantity      positive number of resource units to craft
     * @param lightMode     use Lite crafting mode for faster calculation with limited pattern support;
     *                      omitted means false and true requires the craftingLightMode capability
     * @param ignoreMissing allow crafting to wait for missing ingredients; omitted means false
     *                      and true requires the craftingIgnoreMissing capability
     * @param autoStart     client preference to start after calculation; defaults to false. The client must still
     *                      submit the plan
     * @example autoStart false
     * @example itemKey AAAAAAAAAAAAAAAAAAAAAA
     * @example quantity 64
     * @example lightMode false
     * @example ignoreMissing false
     */
    @Desugar
    public record Input(@NotNull StableKey itemKey, long quantity, @OptionalInput boolean lightMode,
        @OptionalInput boolean ignoreMissing, @OptionalInput boolean autoStart) {}

    @Body
    @NotNull
    Input input;

    @Override
    public boolean init(RequestContext context) {
        if (!super.init(context)) return false;
        if (input.quantity() <= 0) {
            deny(ApiStatus.INVALID_QUANTITY);
            return false;
        }
        return true;
    }

    @Override
    protected void handle(IAEGrid grid) {
        if (grid == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        IAEKey itemKey;
        try {
            itemKey = itemIdentities.resolve(input.itemKey());
        } catch (ItemIdentityRegistry.Ambiguous e) {
            deny(ApiStatus.AMBIGUOUS_ITEM_KEY);
            return;
        }
        if (itemKey == null) {
            deny(ApiStatus.ITEM_IDENTITY_UNKNOWN);
            return;
        }
        IAECraftingGrid craftingGrid = grid.web$getCraftingGrid();
        if (input.lightMode() && !Boolean.TRUE.equals(
            AE2Controller.AE2Interface.web$getCapabilities()
                .get(ServerCapability.CRAFTING_LIGHT_MODE))) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        if (input.ignoreMissing() && !Boolean.TRUE.equals(
            AE2Controller.AE2Interface.web$getCapabilities()
                .get(ServerCapability.CRAFTING_IGNORE_MISSING))) {
            deny(ApiStatus.BAD_PARAM);
            return;
        }
        if (!craftingGrid.web$isCurrentlyCraftable(itemKey)) {
            deny(ApiStatus.ITEM_NOT_FOUND);
            return;
        }
        CraftingOptions options = new CraftingOptions(input.lightMode(), input.ignoreMissing());
        ResourceSnapshot finalOutput = ResourceSnapshot.capture(itemKey, input.quantity(), input.itemKey());
        Future<IAECraftingJob> job = craftingGrid.web$beginCraftingJob(grid, itemKey, input.quantity(), options);
        int jobId = gridData.addJob(job, finalOutput, options, input.autoStart());
        respond(HttpURLConnection.HTTP_ACCEPTED, new Response(ApiStatus.OK, new PlanCreated(jobId)));
    }

}
