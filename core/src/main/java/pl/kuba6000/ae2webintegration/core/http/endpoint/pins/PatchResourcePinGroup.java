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
 * Update Home pin group.
 * <p>
 * Renames or moves the current account's group. Omitted fields remain unchanged.
 *
 * @pathParam groupKey Group identifier.
 * @response 200 {@link Response} Updated account pins and groups.
 * @response 400 {@link pl.kuba6000.ae2webintegration.core.http.ErrorResponse} BAD_PARAM: invalid name or move.
 * @response 404 {@link pl.kuba6000.ae2webintegration.core.http.ErrorResponse} NOT_FOUND: the owned group does not
 *           exist.
 * @response 409 {@link pl.kuba6000.ae2webintegration.core.http.ErrorResponse} GROUP_NAME_TAKEN: name is already used.
 */
@Endpoint(method = HttpMethod.PATCH, path = "/api/resource-pin-groups/{groupKey}")
public final class PatchResourcePinGroup extends ResourcePinRequest {

    /**
     * @param name replacement group name, trimmed and limited to 128 characters
     * @param move {@code earlier} or {@code later} in the account's group order
     */
    @Desugar
    public record Input(@OptionalInput @Nullable String name, @OptionalInput @Nullable String move) {}

    @PathParam("groupKey")
    @NotNull
    StableKey groupKey;
    @Body
    @NotNull
    Input input;

    @Override
    public void handle() {
        ResourcePinStore store = store();
        if (store == null) return;
        try {
            ResourcePinStore.Direction direction = input.move() == null ? null
                : ResourcePinStore.Direction.parse(input.move());
            if (!store.updateGroup(context.getPrincipal(), groupKey, input.name(), direction)) {
                deny(ApiStatus.NOT_FOUND);
                return;
            }
            reply(store);
        } catch (ResourcePinStore.GroupNameTaken conflict) {
            deny(ApiStatus.GROUP_NAME_TAKEN);
        } catch (IllegalArgumentException invalid) {
            deny(ApiStatus.BAD_PARAM);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
