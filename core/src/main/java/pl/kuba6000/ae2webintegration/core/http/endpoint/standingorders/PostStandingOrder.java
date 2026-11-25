package pl.kuba6000.ae2webintegration.core.http.endpoint.standingorders;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.ErrorResponse;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderDefinition;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderEditor;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStore;

/**
 * Create standing order.
 *
 * <p>
 * Adds an order after the network's others. Orders belong to the network. The network's web player calculates
 * plans; an order targeting a machine-only CPU submits using that CPU's native machine source.
 * Creating needs network access and, unless the account is an administrator, the server's
 * allow_players setting. Every resource must be known to the network: open its terminal before choosing a resource
 * that was never stored. Creating an order may make the server read the whole network once; such reads are spaced
 * by the server and never refused.
 *
 * @pathParam gridKey Persistent network identifier.
 * @response 200 {@link Response} The network's standing orders including the new one.
 * @response 400 {@link Rejected} BAD_PARAM: invalid request parameters or body. INVALID_STANDING_ORDER: the definition
 *           violates the format, a limit or the server's capabilities; data names the reason and the member.
 * @response 401 {@link ErrorResponse} UNAUTHORIZED: authentication required. Includes WWW-Authenticate: Bearer.
 * @response 403 {@link ErrorResponse} NO_PERMISSIONS: access to this network denied, or only administrators may create
 *           standing orders. STANDING_ORDERS_DISABLED: standing orders are disabled on the server. CSRF_REJECTED:
 *           cookie or configured-local mutations require X-AE2-Request: true.
 * @response 404 {@link ErrorResponse} GRID_NOT_FOUND: network not found. ITEM_NOT_FOUND: a resource is not known to
 *           the network.
 * @response 405 {@link ErrorResponse} METHOD_NOT_ALLOWED: unsupported method. Allow lists supported methods.
 * @response 409 {@link ErrorResponse} STANDING_ORDER_LIMIT_REACHED: the network holds the maximum number of orders.
 * @response 413 {@link ErrorResponse} REQUEST_TOO_LARGE: the request body exceeds 8192 bytes.
 * @response 415 {@link ErrorResponse} UNSUPPORTED_MEDIA_TYPE: a nonempty request body requires Content-Type:
 *           application/json.
 * @response 429 {@link ErrorResponse} TOO_MANY_REQUESTS: the unauthenticated request rate limit was exceeded.
 * @response 500 {@link ErrorResponse} INTERNAL_ERROR: unexpected server error.
 * @response 503 {@link ErrorResponse} STANDING_ORDERS_UNAVAILABLE or SERVER_STOPPING: orders could not be loaded or the
 *           server is stopping.
 * @responseExample 400
 *                  {"status":"INVALID_STANDING_ORDER","data":{"reason":"AMOUNT_NOT_POSITIVE","path":"trigger.amount"}}
 * @responseExample 401 {"status":"UNAUTHORIZED","data":null}
 * @responseExample 403 {"status":"STANDING_ORDERS_DISABLED","data":null}
 * @responseExample 404 {"status":"ITEM_NOT_FOUND","data":null}
 * @responseExample 405 {"status":"METHOD_NOT_ALLOWED","data":null}
 * @responseExample 409 {"status":"STANDING_ORDER_LIMIT_REACHED","data":null}
 * @responseExample 413 {"status":"REQUEST_TOO_LARGE","data":null}
 * @responseExample 415 {"status":"UNSUPPORTED_MEDIA_TYPE","data":null}
 * @responseExample 429 {"status":"TOO_MANY_REQUESTS","data":null}
 * @responseExample 500 {"status":"INTERNAL_ERROR","data":null}
 * @responseExample 503 {"status":"STANDING_ORDERS_UNAVAILABLE","data":null}
 */
@Endpoint(method = HttpMethod.POST, path = "/api/grids/{gridKey}/standing-orders")
public final class PostStandingOrder extends StandingOrderRequest {

    @Body
    @NotNull
    StandingOrderJson.Definition input;

    @Override
    public void handle() {
        StandingOrderEditor editor = editor();
        if (editor == null || refusesNewWork()) return;
        StandingOrderDefinition definition = definition(input);
        if (definition == null) return;
        try {
            editor.create(gridKey, context.getPrincipal(), definition, descriptions(definition));
        } catch (InvalidStandingOrderException invalid) {
            reject(invalid);
            return;
        } catch (StandingOrderStore.OrderLimitReached full) {
            deny(ApiStatus.STANDING_ORDER_LIMIT_REACHED);
            return;
        } catch (StandingOrderStore.UnknownResource unknown) {
            deny(ApiStatus.ITEM_NOT_FOUND);
            return;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        reply();
    }
}
