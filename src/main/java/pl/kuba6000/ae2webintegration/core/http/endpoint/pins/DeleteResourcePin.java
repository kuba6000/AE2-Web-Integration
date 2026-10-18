package pl.kuba6000.ae2webintegration.core.http.endpoint.pins;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;

/**
 * Removes an owned pin even when grid access has ended; absent or other-owner tokens are idempotent no-ops.
 * 
 * @pathParam pinKey Opaque pin identifier.
 * @response 200 {@link Response} Remaining owner pins.
 */
@Endpoint(method = HttpMethod.DELETE, path = "/api/resource-pins/{pinKey}")
public final class DeleteResourcePin extends ResourcePinRequest {

    @PathParam("pinKey")
    private @NotNull StableKey pinKey;

    @Override
    public void handle() {
        var store = store();
        if (store == null) return;
        try {
            store.remove(context.getPrincipal(), pinKey);
            reply(store);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
