package pl.kuba6000.ae2webintegration.core.http.endpoint.pins;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore.Placement;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/**
 * Add resource pin.
 * <p>
 * Pins a resource to the terminal or Home for the current account. Adding an existing placement leaves the pin
 * unchanged. Other placements and display preferences are preserved.
 * 
 * @pathParam gridKey Network identifier.
 * @response 200 {@link Response} Updated account pins.
 * @response 400 {@link pl.kuba6000.ae2webintegration.core.http.ErrorResponse} BAD_PARAM: invalid placement or input.
 * @response 404 {@link pl.kuba6000.ae2webintegration.core.http.ErrorResponse} ITEM_NOT_FOUND: resource not found in
 *           this network's records; refresh the terminal before retrying.
 */
@Endpoint(method = HttpMethod.POST, path = "/api/grids/{gridKey}/resource-pins")
public final class PostResourcePin extends ResourcePinRequest {

    /**
     * @param itemKey   resource identifier
     * @param placement {@code terminal} or {@code home}
     */
    @Desugar
    public record Input(@NotNull StableKey itemKey, @NotNull String placement) {}

    @Body
    @NotNull
    Input input;

    @Override
    public void handle() {
        ResourcePinStore store = store();
        if (store == null) return;
        try {
            Placement placement = Placement.parse(input.placement());
            TerminalRegistry terminals = CoreEngine.getTerminalRegistry();
            ResourceDescriptor descriptor = terminals == null ? null : terminals.description(gridKey, input.itemKey());
            if (!store.add(context.getPrincipal(), gridKey, input.itemKey(), descriptor, placement)) {
                deny(ApiStatus.ITEM_NOT_FOUND);
                return;
            }
            reply(store);
        } catch (IllegalArgumentException invalid) {
            deny(ApiStatus.BAD_PARAM);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
