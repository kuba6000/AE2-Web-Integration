package pl.kuba6000.ae2webintegration.core.http.endpoint.pins;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.jetbrains.annotations.NotNull;

import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore;

/**
 * Delete resource pin.
 * <p>
 * Removes both placements of the current account's pin. Network access is not required.
 * Missing pins and pins owned by another account are ignored.
 * 
 * @pathParam pinKey Pin identifier.
 * @response 200 {@link Response} Remaining account pins.
 */
@Endpoint(method = HttpMethod.DELETE, path = "/api/resource-pins/{pinKey}")
public final class DeleteResourcePin extends ResourcePinRequest {

    @PathParam("pinKey")
    @NotNull
    StableKey pinKey;

    @Override
    public void handle() {
        ResourcePinStore store = store();
        if (store == null) return;
        try {
            store.remove(context.getPrincipal(), pinKey);
            reply(store);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
