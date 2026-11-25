package pl.kuba6000.ae2webintegration.core.http.endpoint.standingorders;

import static java.net.HttpURLConnection.HTTP_OK;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.bsideup.jabel.Desugar;

import pl.kuba6000.ae2webintegration.core.AE2Controller;
import pl.kuba6000.ae2webintegration.core.CoreEngine;
import pl.kuba6000.ae2webintegration.core.ae2request.async.IAsyncRequest;
import pl.kuba6000.ae2webintegration.core.config.Config;
import pl.kuba6000.ae2webintegration.core.config.ConfigSettings;
import pl.kuba6000.ae2webintegration.core.grid.GridPersistentData;
import pl.kuba6000.ae2webintegration.core.http.ApiStatus;
import pl.kuba6000.ae2webintegration.core.http.IResponseBody;
import pl.kuba6000.ae2webintegration.core.http.contract.QueryParam;
import pl.kuba6000.ae2webintegration.core.http.dto.ResourceDescription;
import pl.kuba6000.ae2webintegration.core.icons.IconMappings;
import pl.kuba6000.ae2webintegration.core.icons.IconPack;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.standingorders.InvalidStandingOrderException;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderDefinition;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderEditor;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderEngine;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderSnapshot;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderStore;
import pl.kuba6000.ae2webintegration.core.standingorders.StandingOrderValidator;
import pl.kuba6000.ae2webintegration.core.terminal.TerminalRegistry;
import pl.kuba6000.ae2webintegration.core.tracking.ResourceDescriptor;

/** Shared standing order request handling: authorization, definition parsing and the listing response. */
public abstract class StandingOrderRequest extends IAsyncRequest {

    /** Include icon references and mappings when an icon pack is available. */
    @QueryParam("icons")
    boolean icons;

    /**
     * @param status {@code OK} when the request succeeds
     * @param data   the network's standing orders and what the current account may do with them
     * @param icons  mappings for resource icon references, or null when not requested or unavailable
     */
    @Desugar
    public record Response(@NotNull ApiStatus status, @NotNull Listing data, @Nullable IconMappings icons)
        implements IResponseBody {}

    /**
     * @param status always the machine-readable error code
     * @param data   the offending member for INVALID_STANDING_ORDER; null for every other error
     */
    @Desugar
    public record Rejected(@NotNull ApiStatus status, @Nullable Rejection data) implements IResponseBody {}

    /**
     * Why a definition was refused, or why a stored one cannot be used.
     *
     * @param reason stable code, for example {@code AMOUNT_NOT_POSITIVE} or {@code NAME_LENGTH}
     * @param path   location of the offending member in the definition, for example
     *               {@code trigger.children[1].amount}; empty when the whole definition is concerned
     * @example reason AMOUNT_NOT_POSITIVE
     * @example path trigger.children[1].amount
     */
    @Desugar
    public record Rejection(@NotNull InvalidStandingOrderException.Reason reason, @NotNull String path) {}

    /**
     * @param serverEnabled whether standing orders are enabled on the server; when false nothing runs, orders can
     *                      only be deleted or switched off, and none can be created, switched on or run
     * @param gridEnabled   whether standing orders are switched on for this network
     * @param canEdit       whether the current account may change orders: it needs the server's allow_players setting
     *                      unless it is an administrator. Does not account for serverEnabled.
     * @param maxOrders     orders this network may hold
     * @param orders        the network's orders in display order
     * @param resources     descriptions of the resources the orders refer to, by resource identifier
     */
    @Desugar
    public record Listing(boolean serverEnabled, boolean gridEnabled, boolean canEdit, int maxOrders,
        @NotNull List<Order> orders, @NotNull Map<StableKey, ResourceDescription> resources) {}

    /**
     * One standing order. Exactly one of definition and invalid is set.
     *
     * @param orderKey   order identifier
     * @param definition the order's settings, or null when the stored definition cannot be read
     * @param invalid    why the stored definition cannot be read, or null; such an order can be replaced or deleted
     * @param status     latest runtime state; null until the engine evaluated the order for the first time
     */
    @Desugar
    public record Order(@NotNull StableKey orderKey, @Nullable StandingOrderJson.Definition definition,
        @Nullable Rejection invalid, @Nullable StandingOrderSnapshot status) {}

    private static final class OrderResource extends ResourceDescription {

        private OrderResource(@NotNull ResourceDescriptor resource, @Nullable IconMappings mappings) {
            super(resource, mappings);
        }
    }

    protected final @Nullable StandingOrderEditor editor() {
        StandingOrderEditor editor = CoreEngine.getStandingOrderEditor();
        if (editor == null) deny(ApiStatus.STANDING_ORDERS_UNAVAILABLE);
        return editor;
    }

    /**
     * Changing orders needs grid access, checked on init, and the server's players permission; answers and returns
     * true when the change is not allowed. Deleting and switching orders off stay possible while standing orders are
     * disabled, so that they can be cleaned up.
     */
    protected final boolean refusesChange() {
        if (!Config.INSTANCE.standingOrders.canEdit(context.getPrincipal())) {
            deny(ApiStatus.NO_PERMISSIONS);
            return true;
        }
        return false;
    }

    /**
     * Creating and running orders additionally need standing orders enabled on the server; answers and returns true
     * when the change is not allowed.
     */
    protected final boolean refusesNewWork() {
        if (refusesChange()) return true;
        if (!Config.INSTANCE.standingOrders.enabled) {
            deny(ApiStatus.STANDING_ORDERS_DISABLED);
            return true;
        }
        return false;
    }

    /**
     * Parses a definition and checks the server supports its options.
     *
     * @throws InvalidStandingOrderException the definition violates the format, a limit or the capabilities
     */
    private @NotNull StandingOrderDefinition parse(@NotNull StandingOrderJson.Definition body,
        boolean checkCapabilities) throws InvalidStandingOrderException {
        StandingOrderDefinition definition = StandingOrderMapping.decode(body);
        if (checkCapabilities)
            StandingOrderValidator.validateCapabilities(definition, AE2Controller.AE2Interface.web$getCapabilities());
        return definition;
    }

    /**
     * The stored definition with the changes applied, parsed like a submitted one. Server capabilities are checked
     * only for what the change supplies: an order stored with an option the server no longer supports can still be
     * renamed or switched off, but a supplied action or a repair of an unreadable order must be supported.
     */
    protected final @NotNull StandingOrderDefinition merged(@NotNull StandingOrderStore.Entry current,
        @NotNull StandingOrderJson.Changes changes) throws InvalidStandingOrderException {
        boolean stored = current instanceof StandingOrderStore.Valid;
        return parse(
            StandingOrderMapping
                .merge(current instanceof StandingOrderStore.Valid valid ? valid.definition() : null, changes),
            changes.action() != null || !stored);
    }

    /** Parses the submitted definition; answers and returns null if it is refused. */
    protected final @Nullable StandingOrderDefinition definition(@NotNull StandingOrderJson.Definition body) {
        try {
            return parse(body, true);
        } catch (InvalidStandingOrderException invalid) {
            reject(invalid);
            return null;
        }
    }

    /** Descriptions the terminal currently holds for the definition's resources; others must already be stored. */
    protected final @NotNull Map<StableKey, ResourceDescriptor> descriptions(
        @NotNull StandingOrderDefinition definition) {
        Map<StableKey, ResourceDescriptor> result = new HashMap<>();
        TerminalRegistry terminals = CoreEngine.getTerminalRegistry();
        if (terminals == null) return result;
        for (StableKey resource : definition.resources()) {
            ResourceDescriptor description = terminals.description(gridKey, resource);
            if (description != null) result.put(resource, description);
        }
        return result;
    }

    /** Answers a definition that violates the format, a limit or the server's capabilities. */
    protected final void reject(@NotNull InvalidStandingOrderException invalid) {
        deny(ApiStatus.INVALID_STANDING_ORDER, new Rejection(invalid.reason(), invalid.path()));
    }

    /** Answers with the network's current standing orders. */
    protected final void reply() {
        StandingOrderStore store = CoreEngine.getStandingOrderStore();
        StandingOrderEngine engine = CoreEngine.getStandingOrders();
        GridPersistentData data = CoreEngine.GRID_IDENTITIES.getPersistentData(gridKey);
        if (store == null || engine == null) {
            deny(ApiStatus.STANDING_ORDERS_UNAVAILABLE);
            return;
        }
        if (data == null) {
            deny(ApiStatus.GRID_NOT_FOUND);
            return;
        }
        StandingOrderStore.Snapshot snapshot;
        try {
            snapshot = store.snapshot(gridKey);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        IconPack pack = icons ? CoreEngine.getIconPack() : null;
        IconMappings mappings = pack == null ? null : new IconMappings(pack);
        List<Order> orders = new ArrayList<>();
        for (StandingOrderStore.Entry entry : snapshot.orders()) {
            StandingOrderSnapshot status = engine.snapshot(entry.key());
            if (entry instanceof StandingOrderStore.Valid valid) {
                orders.add(new Order(valid.key(), StandingOrderMapping.encode(valid.definition()), null, status));
            } else {
                InvalidStandingOrderException failure = ((StandingOrderStore.Invalid) entry).failure();
                orders.add(new Order(entry.key(), null, new Rejection(failure.reason(), failure.path()), status));
            }
        }
        Map<StableKey, ResourceDescription> resources = new LinkedHashMap<>();
        for (Map.Entry<StableKey, ResourceDescriptor> resource : snapshot.resources()
            .entrySet()) {
            resources.put(resource.getKey(), new OrderResource(resource.getValue(), mappings));
        }
        ConfigSettings.StandingOrders settings = Config.INSTANCE.standingOrders;
        boolean canEdit = settings.canEdit(context.getPrincipal());
        respondDetached(
            HTTP_OK,
            new Response(
                ApiStatus.OK,
                new Listing(
                    settings.enabled,
                    data.getSettings()
                        .isStandingOrdersEnabled(),
                    canEdit,
                    settings.maxOrdersPerGrid,
                    orders,
                    resources),
                mappings));
    }
}
