package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import java.util.IdentityHashMap;
import java.util.Set;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.google.common.collect.ImmutableList;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.helpers.IInterfaceHost;
import appeng.me.cache.CraftingGridCache;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import pl.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingMediumKey;
import pl.kuba6000.ae2webintegration.core.interfaces.ICraftingMediumTracker;
import pl.kuba6000.ae2webintegration.core.interfaces.IPatternProviderViewable;
import pl.kuba6000.ae2webintegration.core.interfaces.service.IAECraftingGrid;

@Mixin(value = CraftingGridCache.class, remap = false)
public class CraftingGridCacheMixin implements ICraftingMediumTracker {

    @Final
    @Shadow
    private IGrid grid;

    @Shadow
    @Final
    private Object2ObjectMap<IAEItemStack, ImmutableList<ICraftingPatternDetails>> craftableItems;

    @Shadow
    @Final
    private Set<IAEItemStack> emitableItems;

    @Shadow
    private boolean updatePatterns;

    public boolean web$isCurrentlyCraftable(IAEKey key) {
        // Native fluid crafting remains disabled on this adapter.
        return key instanceof IAEItemStack && !updatePatterns
            && (craftableItems.containsKey(key) || emitableItems.contains(key));
    }

    @Unique
    private final IdentityHashMap<ICraftingMedium, IInterfaceHost> web$mediumToViewable = new IdentityHashMap<>();

    @Unique
    private ICraftingProvider web$currentCraftingProvider;

    @Inject(method = "submitJob", at = @At("RETURN"))
    void ae2webintegration$submitJob(final ICraftingJob job, final ICraftingRequester requestingMachine,
        final ICraftingCPU target, final boolean prioritizePower, final IActionSource src,
        CallbackInfoReturnable<ICraftingLink> cir) {
        ICraftingLink link = cir.getReturnValue();
        if (link != null) { // job started successfully
            boolean isMachine = (requestingMachine != null || src.machine()
                .isPresent()) && !src.player()
                    .isPresent();
            String requester = null;
            if (src != null && src.player().isPresent()) {
                String name = src.player().get().getName();
                requester = "AE2CONTROLLER".equals(name) ? "Web Panel" : name;
            } else if (requestingMachine != null) {
                requester = "Machine (" + requestingMachine.getClass().getSimpleName() + ")";
            } else if (src != null && src.machine().isPresent()) {
                requester = "Machine";
            }
            IAEMixinCallbacks.getInstance()
                .jobStarted(
                    (ICraftingCPUCluster) (Object) ((CraftingLinkAccessor) (Object) link).callGetCpu(),
                    (IAECraftingGrid) this,
                    (IAEGrid) grid,
                    false,
                    !isMachine,
                    requester);
        }
    }

    @Inject(
        method = "recalculateCraftingPatterns",
        at = @At(value = "INVOKE", target = "Ljava/util/Set;clear()V", ordinal = 0, shift = At.Shift.AFTER))
    void ae2webintegration$updatePatternsStart(CallbackInfo ci) {
        web$mediumToViewable.clear();
        web$currentCraftingProvider = null;
    }

    @Redirect(
        method = "recalculateCraftingPatterns",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/crafting/ICraftingProvider;provideCrafting(Lappeng/api/networking/crafting/ICraftingProviderHelper;)V"))
    void ae2webintegration$provideCrafting(ICraftingProvider instance,
        ICraftingProviderHelper iCraftingProviderHelper) {
        web$currentCraftingProvider = instance;
        try {
            instance.provideCrafting(iCraftingProviderHelper);
        } finally {
            web$currentCraftingProvider = null;
        }
    }

    @Inject(method = "addCraftingOption", at = @At("HEAD"))
    void ae2webintegration$addCraftingOption(ICraftingMedium medium, ICraftingPatternDetails api, CallbackInfo ci) {
        if (web$currentCraftingProvider instanceof IInterfaceHost viewable
            && !web$mediumToViewable.containsKey(medium)) {
            web$mediumToViewable.put(medium, viewable);
        }
    }

    @Override
    public IPatternProviderViewable web$getViewableForCraftingMedium(ICraftingMediumKey medium) {
        return (IPatternProviderViewable) web$mediumToViewable.get((ICraftingMedium) medium);
    }

    public ICraftingMediumTracker web$getCraftingProviders() {
        return (ICraftingMediumTracker) this;
    }

}
