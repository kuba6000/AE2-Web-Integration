package pl.kuba6000.ae2webintegration.core.http.endpoint.pins;

import java.io.IOException;
import java.io.UncheckedIOException;

import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;

/**
 * Lists this account's resource pins. Unavailable grids expose only pin management fields.
 * 
 * @response 200 {@link Response} Current owner pins and optional response-local icons.
 */
@Endpoint(method = HttpMethod.GET, path = "/api/resource-pins")
public final class GetResourcePins extends ResourcePinRequest {

    @Override
    public void handle() {
        var store = store();
        if (store == null) return;
        try {
            reply(store);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
