package pl.kuba6000.ae2webintegration.core.http.endpoint.pins;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.http.contract.OptionalInput;
import pl.kuba6000.ae2webintegration.core.http.contract.PathParam;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore;

/**
 * Update resource pin.
 * <p>
 * Updates the current account's pin. Omitted fields remain unchanged. Disabling both placements deletes the pin.
 * 
 * @pathParam pinKey Pin identifier.
 * @response 200 {@link Response} Updated account pins.
 * @response 400 {@link pl.kuba6000.ae2webintegration.core.http.ErrorResponse} BAD_PARAM: invalid configuration, range
 *           or move.
 * @response 403 {@link pl.kuba6000.ae2webintegration.core.http.ErrorResponse} NO_PERMISSIONS: enabling a placement
 *           requires network access.
 * @response 404 {@link pl.kuba6000.ae2webintegration.core.http.ErrorResponse} NOT_FOUND: the owned pin does not exist.
 */
@Endpoint(method = HttpMethod.PATCH, path = "/api/resource-pins/{pinKey}")
public final class PatchResourcePin extends ResourcePinRequest {

    /**
     * @param placement {@code terminal} or {@code home}
     * @param direction {@code earlier} or {@code later} in the selected placement's order
     */
    @Desugar
    public record MoveInput(@NotNull String placement, @NotNull String direction) {}

    /**
     * @param terminal   whether to show the pin in this network's terminal
     * @param home       whether to show the pin on Home
     * @param move       move the pin within one placement
     * @param homeConfig complete replacement of the Home card's display settings
     */
    @Desugar
    public record Input(@OptionalInput @Nullable Boolean terminal, @OptionalInput @Nullable Boolean home,
        @OptionalInput @Nullable MoveInput move, @OptionalInput @Nullable ResourcePinStore.HomeConfig homeConfig) {}

    @PathParam("pinKey")
    @NotNull
    StableKey pinKey;
    @Body
    @NotNull
    Input input;

    @Override
    public void handle() {
        ResourcePinStore store = store();
        if (store == null) return;
        try {
            ResourcePinStore.Move move = input.move() == null ? null
                : new ResourcePinStore.Move(
                    ResourcePinStore.Placement.parse(
                        input.move()
                            .placement()),
                    ResourcePinStore.Direction.parse(
                        input.move()
                            .direction()));
            if (input.homeConfig() != null) input.homeConfig()
                .validate();
            ResourcePinStore.Pin pin = store.list(context.getPrincipal())
                .stream()
                .filter(
                    value -> value.pinKey()
                        .equals(pinKey))
                .findFirst()
                .orElse(null);
            if (pin == null) {
                deny(ApiStatus.NOT_FOUND);
                return;
            }
            if ((Boolean.TRUE.equals(input.terminal()) || Boolean.TRUE.equals(input.home()))
                && !available(pin.gridKey())) {
                deny(ApiStatus.NO_PERMISSIONS);
                return;
            }
            if (!store
                .update(context.getPrincipal(), pinKey, input.terminal(), input.home(), move, input.homeConfig())) {
                deny(ApiStatus.NOT_FOUND);
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
