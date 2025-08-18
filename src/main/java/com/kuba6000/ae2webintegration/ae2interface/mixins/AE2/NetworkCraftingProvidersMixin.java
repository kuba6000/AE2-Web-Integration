package com.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import java.util.IdentityHashMap;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.kuba6000.ae2webintegration.ae2interface.accessors.IProviderState;
import com.kuba6000.ae2webintegration.core.interfaces.ICraftingMediumTracker;

import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.me.service.helpers.NetworkCraftingProviders;

@Mixin(value = NetworkCraftingProviders.class, remap = false)
public class NetworkCraftingProvidersMixin implements ICraftingMediumTracker {

    @Unique
    private final Map<ICraftingProvider, IGridNode> web$providerToNode = new IdentityHashMap<>();

    @Redirect(
        method = "addProvider",
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
        method = "removeProvider",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"),
        remap = false)
    private Object onRemoveProvider(Map<IGridNode, IProviderState> map, Object node) {
        IProviderState returned = map.remove(node);
        if (returned != null) web$providerToNode.remove(returned.web$getProvider());
        return returned;
    }

    @Override
    public Map<ICraftingProvider, IGridNode> web$getCraftingMediums() {
        return web$providerToNode;
    }
}
