package pl.kuba6000.ae2webintegration.core.http.endpoint.pins;

import java.io.IOException;
import java.io.UncheckedIOException;

import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore;

/**
 * List resource pins.
 * <p>
 * Returns the current account's pins. Pins for unavailable networks retain their placement and display settings,
 * but hide network and resource details.
 * 
 * @response 200 {@link Response} Account pins and requested icon mappings.
 */
@Endpoint(method = HttpMethod.GET, path = "/api/resource-pins")
public final class GetResourcePins extends ResourcePinRequest {

    @Override
    public void handle() {
        ResourcePinStore store = store();
        if (store == null) return;
        try {
            reply(store);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
