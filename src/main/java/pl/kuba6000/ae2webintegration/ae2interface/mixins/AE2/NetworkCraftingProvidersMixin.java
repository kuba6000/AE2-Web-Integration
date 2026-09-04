package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.me.service.helpers.NetworkCraftingProviders;
import pl.kuba6000.ae2webintegration.ae2interface.accessors.IProviderState;
import pl.kuba6000.ae2webintegration.core.identity.OrderableResources;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingMediumKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingMediumTracker;
import pl.kuba6000.ae2webintegration.core.interfaces.IOrderableResourceProvider;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;

@Mixin(value = NetworkCraftingProviders.class, remap = false)
public class NetworkCraftingProvidersMixin implements ICraftingMediumTracker, IOrderableResourceProvider {

    @Unique
    private final Map<ICraftingProvider, IGridNode> web$providerToNode = new IdentityHashMap<>();

    @Unique
    private OrderableResources web$orderableResources;

    @Override
    @SuppressWarnings("unchecked")
    public OrderableResources web$getOrderableResources() {
        if (web$orderableResources == null) {
            NetworkCraftingProviders providers = (NetworkCraftingProviders) (Object) this;
            web$orderableResources = new OrderableResources(
                () -> (Iterator<IAEKey>) (Iterator<?>) providers.getCraftableKeys()
                    .iterator(),
                () -> (Iterator<IAEKey>) (Iterator<?>) providers.getEmittableKeys()
                    .iterator(),
                key -> providers.getCraftableKeys()
                    .contains(key)
                    || providers.getEmittableKeys()
                        .contains(key),
                () -> true);
        }
        return web$orderableResources;
    }

    @Inject(
        method = { "addProvider(Lappeng/api/networking/IGridNode;)V",
            "removeProvider(Lappeng/api/networking/IGridNode;)V" },
        at = @At("HEAD"),
        remap = false)
    private void web$beginProviderUpdate(CallbackInfo callback) {
        web$getOrderableResources().beginUpdate();
    }

    @Inject(
        method = { "addProvider(Lappeng/api/networking/IGridNode;)V",
            "removeProvider(Lappeng/api/networking/IGridNode;)V" },
        at = @At("RETURN"),
        remap = false)
    private void web$endProviderUpdate(CallbackInfo callback) {
        // Exceptional exits leave the resource view unavailable after a potentially partial mutation.
        web$getOrderableResources().endUpdate();
    }

    @Redirect(
        method = "addProvider(Lappeng/api/networking/IGridNode;)V",
        at = @At(
            value = "INVOKE",
            target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
        remap = false)
    private Object onAddProvider(Map<IGridNode, IProviderState> map, Object node, Object state) {
        IProviderState returned = map.put((IGridNode) node, (IProviderState) state);
        web$providerToNode.put(((IProviderState) state).web$getProvider(), (IGridNode) node);
        return returned;
    }

    @Redirect(
        method = "removeProvider(Lappeng/api/networking/IGridNode;)V",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"),
        remap = false)
    private Object onRemoveProvider(Map<IGridNode, IProviderState> map, Object node) {
        IProviderState returned = map.remove(node);
        if (returned != null) web$providerToNode.remove(returned.web$getProvider());
        return returned;
    }

    @Override
    public IPatternProviderViewable web$getViewableForCraftingMedium(ICraftingMediumKey medium) {
        return (IPatternProviderViewable) web$providerToNode.get((ICraftingProvider) medium);
    }
}
