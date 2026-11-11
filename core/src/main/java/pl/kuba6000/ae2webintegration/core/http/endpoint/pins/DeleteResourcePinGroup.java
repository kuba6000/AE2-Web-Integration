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
 * Delete Home pin group.
 * <p>
 * Moves the group's pins to Ungrouped and removes the group. Pins and their settings are preserved.
 * Missing groups and groups owned by another account are ignored.
 *
 * @pathParam groupKey Group identifier.
 * @response 200 {@link Response} Updated account pins and groups.
 */
@Endpoint(method = HttpMethod.DELETE, path = "/api/resource-pin-groups/{groupKey}")
public final class DeleteResourcePinGroup extends ResourcePinRequest {

    @PathParam("groupKey")
    @NotNull
    StableKey groupKey;

    @Override
    public void handle() {
        ResourcePinStore store = store();
        if (store == null) return;
        try {
            store.removeGroup(context.getPrincipal(), groupKey);
            reply(store);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
