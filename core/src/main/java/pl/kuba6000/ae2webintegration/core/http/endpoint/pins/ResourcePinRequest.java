package pl.kuba6000.ae2webintegration.core.http.endpoint.pins;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.grid.GridAccess;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.dto.ResourceDescription;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.pins.ResourcePinStore;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;
import pl.kuba6000.ae2webintegration.core.tracking.storage.RecentResourceActivity;

/** Shared resource pin request and response fields. */
public abstract class ResourcePinRequest extends IAsyncRequest {

    /** Include icon references and mappings when an icon pack is available. */
    @QueryParam("icons")
    boolean icons;

    /**
     * @param status {@code OK} when the request succeeds
     * @param data   current account's pins
     * @param icons  mappings for resource icon references, or null when not requested or unavailable
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull List<View> data, @Nullable IconMappings icons)
        implements IResponseBody {}

    /**
     * @param pinKey        pin identifier
     * @param available     whether the network is available and accessible to the current account
     * @param gridKey       network identifier, or null when unavailable
     * @param itemKey       resource identifier, or null when unavailable
     * @param gridName      network name, or null when unavailable
     * @param resource      resource description, or null when unavailable
     * @param terminalOrder order within this network's terminal pins, or null when not pinned there
     * @param homeOrder     order among all Home pins, or null when not pinned there
     * @param homeConfig    Home card display settings
     * @param recent        recent flow for this resource, or null when unavailable, disabled or not shown on Home
     */
    @Desugar
    public record View(@NotNull StableKey pinKey, boolean available, @Nullable StableKey gridKey,
        @Nullable StableKey itemKey, @Nullable String gridName, @Nullable ResourceDescription resource,
        @Nullable Integer terminalOrder, @Nullable Integer homeOrder, @NotNull ResourcePinStore.HomeConfig homeConfig,
        @Nullable RecentResourceActivity.Summary recent) {}

    private static final class PinResource extends ResourceDescription {

        private PinResource(@NotNull ResourceDescriptor resource, @Nullable IconMappings.Reference icon) {
            super(
                resource.registryNamespace(),
                resource.registryPath(),
                resource.displayName(),
                resource.componentCount(),
                resource.damage(),
                resource.resourceType(),
                resource.itemKey(),
                icon);
        }
    }

    protected final @Nullable ResourcePinStore store() {
        ResourcePinStore store = CoreEngine.getResourcePins();
        if (store == null) deny(ApiStatus.SERVER_STOPPING);
        return store;
    }

    protected final boolean available(@NotNull StableKey key) {
        return GridAccess.allows(CoreEngine.GRID_IDENTITIES.getGrid(key), context.getPrincipal());
    }

    private static @Nullable RecentResourceActivity.Summary recent(@NotNull ResourcePinStore.Pin pin) {
        if (pin.homeOrder() == null || !pin.homeConfig()
            .showRecentFlow()) return null;
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(pin.gridKey());
        if (data == null || !data.getSettings()
            .isFlowTracked()) return null;
        RecentResourceActivity.Summary summary = CoreEngine.recentActivity(pin.gridKey());
        if (summary == null) return null;
        RecentResourceActivity.Amount amount = summary.resources()
            .get(pin.itemKey());
        String zero = summary.flowSince() != null && summary.flowComplete() ? "0" : null;
        return new RecentResourceActivity.Summary(
            summary.flowWindowMillis(),
            summary.stockWindowMillis(),
            summary.bucketMillis(),
            summary.observedAt(),
            summary.flowObservedAt(),
            null,
            summary.flowSince(),
            null,
            summary.flowComplete(),
            false,
            Collections.singletonMap(
                pin.itemKey(),
                new RecentResourceActivity.Amount(
                    amount == null ? zero : amount.input(),
                    amount == null ? zero : amount.output(),
                    amount == null ? zero : amount.inputOperations(),
                    amount == null ? zero : amount.outputOperations(),
                    null)));
    }

    protected final void reply(@NotNull ResourcePinStore store) throws IOException {
        IconPack pack = icons ? CoreEngine.getIconPack() : null;
        IconMappings mappings = pack == null ? null : new IconMappings(pack);
        Map<StableKey, Boolean> access = new HashMap<>();
        List<View> result = new ArrayList<>();
        for (ResourcePinStore.Pin pin : store.list(context.getPrincipal())) {
            boolean allowed = access.computeIfAbsent(pin.gridKey(), this::available);
            result
                .add(
                    new View(
                        pin.pinKey(),
                        allowed,
                        allowed ? pin.gridKey() : null,
                        allowed ? pin.itemKey() : null,
                        allowed ? pin.gridName() : null,
                        allowed
                            ? new PinResource(
                                pin.resource(),
                                mappings == null ? null
                                    : mappings.resolve(
                                        pin.itemKey(),
                                        pin.resource()
                                            .baseKey()))
                            : null,
                        pin.terminalOrder(),
                        pin.homeOrder(),
                        pin.homeConfig(),
                        allowed ? recent(pin) : null));
        }
        respondDetached(HttpURLConnection.HTTP_OK, new Response(ApiStatus.OK, result, mappings));
    }
}
