package pl.kuba6000.ae2webintegration.core.http.endpoint.standingorders;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderEditor;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStore;

/**
 * Update standing order.
 *
 * <p>
 * Changes an order, including its name and whether it is enabled. Omitted members keep their stored value; a present
 * trigger or action replaces that whole part. The merged definition is validated like a new one, and the order keeps
 * its identifier and position. An order whose stored definition cannot be read has no values to keep: it can only be
 * repaired by sending every member. Editing needs network access and, unless the account is an administrator, the
 * server allow_players setting. While standing orders are disabled on the server an order can still be edited and
 * switched off, but not switched on. An edit that makes the order refer to a resource it did not use, or enables it,
 * may make the server read the whole network once; such reads are spaced by the server and never refused.
 *
 * @pathParam gridKey Persistent network identifier.
 * @pathParam orderKey Standing order identifier.
 * @response 200 {@link Response} The network's standing orders with the changed one.
 * @response 400 {@link Rejected} BAD_PARAM: invalid request parameters or body. INVALID_STANDING_ORDER: the merged
 *           definition violates the format, a limit or the server capabilities, or an unreadable order lacks a member
 *           (MISSING_FIELD); data names the reason and the member.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required. Includes WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: access to this network denied, or only administrators may change
 *           standing orders. STANDING_ORDERS_DISABLED: the edit would switch on an order while standing orders are
 *           disabled on the server. CSRF_REJECTED: cookie or configured-local mutations require X-AE2-Request: true.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: network not found. STANDING_ORDER_NOT_FOUND: the network has no
 *           such order. ITEM_NOT_FOUND: a resource is not known to the network.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method. Allow lists supported methods.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 415 {@link ErrorResponse} UNSUPPORTED_MEDIA_TYPE: a nonempty request body requires Content-Type:
 *           application/json.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: unexpected server error.
 * @response 503 {@link ErrorResponse} STANDING_ORDERS_UNAVAILABLE or SERVER_STOPPING: orders could not be loaded or the
 *           server is stopping.
 * @responseExample 400 {"status":"INVALID_STANDING_ORDER","data":{"reason":"NAME_LENGTH","path":""}}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 403 {"status":"STANDING_ORDERS_DISABLED","data":null}
 * @responseExample 404 {"status":"STANDING_ORDER_NOT_FOUND","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 415 {"status":"UNSUPPORTED_MEDIA_TYPE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"STANDING_ORDERS_UNAVAILABLE","data":null}
 */
@Endpoint(method = HttpMethod.PATCH, path = "/api/grids/{gridKey}/standing-orders/{orderKey}")
public final class PatchStandingOrder extends StandingOrderRequest {

    @PathParam("orderKey")
    @NotNull
    StableKey orderKey;
    @Body
    @NotNull
    StandingOrderJson.Changes input;

    @Override
    public void handle() {
        StandingOrderEditor editor = editor();
        if (editor == null || refusesChange()) return;
        try {
            switch (editor.update(
                context.getPrincipal(),
                gridKey,
                orderKey,
                current -> merged(current, input),
                this::descriptions,
                Config.INSTANCE.standingOrders.enabled)) {
                case NOT_FOUND -> deny(ApiStatus.STANDING_ORDER_NOT_FOUND);
                case ENABLING_REFUSED -> deny(ApiStatus.STANDING_ORDERS_DISABLED);
                case UPDATED -> reply();
            }
        } catch (InvalidStandingOrderException invalid) {
            reject(invalid);
        } catch (StandingOrderStore.UnknownResource unknown) {
            deny(ApiStatus.ITEM_NOT_FOUND);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
