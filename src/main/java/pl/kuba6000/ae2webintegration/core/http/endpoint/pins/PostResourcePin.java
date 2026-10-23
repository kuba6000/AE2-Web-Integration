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
 * Enables one resource pin placement without changing its other placement or display preferences.
 * 
 * @pathParam gridKey Accessible grid identifier.
 * @response 200 {@link Response} Current owner pins after idempotent creation.
 * @response 400 {@link pl.kuba6000.ae2webintegration.core.http.ErrorResponse} BAD_PARAM: invalid placement or input.
 * @response 404 {@link pl.kuba6000.ae2webintegration.core.http.ErrorResponse} ITEM_NOT_FOUND: no authoritative
 *           grid-specific description is available; refresh the terminal before retrying.
 */
@Endpoint(method = HttpMethod.POST, path = "/api/grids/{gridKey}/resource-pins")
public final class PostResourcePin extends ResourcePinRequest {

    /** @param placement terminal or home; the other placement remains unchanged. */
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
