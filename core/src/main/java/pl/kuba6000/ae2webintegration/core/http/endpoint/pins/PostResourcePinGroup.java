package pl.kuba6000.ae2webintegration.core.http.endpoint.pins;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.jetbrains.annotations.NotNull;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.contract.Body;
import pl.kuba6000.ae2webintegration.core.http.contract.Endpoint;
import pl.kuba6000.ae2webintegration.core.http.contract.HttpMethod;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore;

/**
 * Create Home pin group.
 * <p>
 * Adds a named group for the current account, up to 64 groups. Groups can contain Home pins from different networks.
 *
 * @response 200 {@link Response} Updated account pins and groups.
 * @response 400 {@link pl.kuba6000.ae2webintegration.core.http.ErrorResponse} BAD_PARAM: empty or overlong
 *           name.
 * @response 409 {@link pl.kuba6000.ae2webintegration.core.http.ErrorResponse} GROUP_NAME_TAKEN or GROUP_LIMIT_REACHED:
 *           name is already used or the account has 64 groups.
 */
@Endpoint(method = HttpMethod.POST, path = "/api/resource-pin-groups")
public final class PostResourcePinGroup extends ResourcePinRequest {

    /**
     * @param name group name, trimmed and limited to 128 characters
     */
    @Desugar
    public record Input(@NotNull String name) {}

    @Body
    @NotNull
    Input input;

    @Override
    public void handle() {
        ResourcePinStore store = store();
        if (store == null) return;
        try {
            store.createGroup(context.getPrincipal(), input.name());
            reply(store);
        } catch (ResourcePinStore.GroupNameTaken conflict) {
            deny(ApiStatus.GROUP_NAME_TAKEN);
        } catch (ResourcePinStore.GroupLimitReached full) {
            deny(ApiStatus.GROUP_LIMIT_REACHED);
        } catch (IllegalArgumentException invalid) {
            deny(ApiStatus.BAD_PARAM);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
