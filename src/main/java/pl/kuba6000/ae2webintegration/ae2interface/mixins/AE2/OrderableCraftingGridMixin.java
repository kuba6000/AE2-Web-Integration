package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import java.util.Iterator;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.google.common.collect.ImmutableList;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.cache.CraftingGridCache;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import pl.kuba6000.ae2webintegration.core.identity.OrderableResources;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IOrderableResourceProvider;

@Mixin(value = CraftingGridCache.class, remap = false)
public abstract class OrderableCraftingGridMixin implements IOrderableResourceProvider {

    @Shadow
    @Final
    private Object2ObjectMap<IAEItemStack, ImmutableList<ICraftingPatternDetails>> craftableItems;

    @Shadow
    @Final
    private Set<IAEItemStack> emitableItems;

    @Shadow
    private boolean updatePatterns;

    @Unique
    private boolean web$patternsPending;

    @Unique
    private final OrderableResources web$orderableResources = new OrderableResources(
        () -> web$keyIterator(
            craftableItems.keySet()
                .iterator()),
        () -> web$keyIterator(emitableItems.iterator()),
        key -> key instanceof IAEItemStack && (craftableItems.containsKey(key) || emitableItems.contains(key)),
        () -> !updatePatterns && !web$patternsPending);

    @Override
    public OrderableResources web$getOrderableResources() {
        return web$orderableResources;
    }

    @Unique
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Iterator<IAEKey> web$keyIterator(Iterator<IAEItemStack> iterator) {
        return (Iterator) iterator;
    }

    @Inject(
        method = { "updatePatterns", "addNode", "removeNode" },
        at = @At(
            value = "FIELD",
            target = "Lappeng/me/cache/CraftingGridCache;updatePatterns:Z",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER))
    private void web$markPatternsPending(CallbackInfo ci) {
        // A late callback may request another rebuild before onUpdateTick clears the native flag.
        web$patternsPending = true;
    }

    @Inject(method = "recalculateCraftingPatterns", at = @At("HEAD"))
    private void web$beginPatternUpdate(CallbackInfo ci) {
        web$orderableResources.beginUpdate();
        web$patternsPending = false;
    }

    @Inject(method = "setEmitable", at = @At("HEAD"))
    private void web$beginMembershipUpdate(CallbackInfo ci) {
        web$orderableResources.beginUpdate();
    }

    @Inject(method = { "recalculateCraftingPatterns", "setEmitable" }, at = @At("RETURN"))
    private void web$endMembershipUpdate(CallbackInfo ci) {
        web$orderableResources.endUpdate();
    }
}
