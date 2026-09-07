package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2;

import java.util.IdentityHashMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.PlayerSource;
import appeng.api.util.IInterfaceViewable;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import pl.kuba6000.ae2webintegration.core.api.IAEMixinCallbacks;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
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

    @Unique
    private final IdentityHashMap<ICraftingMedium, IInterfaceViewable> web$mediumToViewable = new IdentityHashMap<>();

    @Unique
    private ICraftingProvider web$currentCraftingProvider;

    @Redirect(
        method = "submitJob(Lappeng/api/networking/crafting/ICraftingJob;Lappeng/api/networking/crafting/ICraftingRequester;Lappeng/api/networking/crafting/ICraftingCPU;ZLappeng/api/networking/security/BaseActionSource;Z)Lappeng/api/networking/crafting/ICraftingLink;",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/me/cluster/implementations/CraftingCPUCluster;submitJob(Lappeng/api/networking/IGrid;Lappeng/api/networking/crafting/ICraftingJob;Lappeng/api/networking/security/BaseActionSource;Lappeng/api/networking/crafting/ICraftingRequester;)Lappeng/api/networking/crafting/ICraftingLink;"))
    ICraftingLink ae2webintegration$submitJob(CraftingCPUCluster instance, IGrid craftID, ICraftingJob whatLink,
        BaseActionSource list, ICraftingRequester e) {
        boolean isMerging = false;
        if (instance.isBusy()) {
            isMerging = true;
        }
        ICraftingLink link = instance.submitJob(craftID, whatLink, list, e);
        if (link != null) { // job started successfully
            boolean isMachine = e != null || list.isMachine();
            String requester = null;
            if (list instanceof PlayerSource) {
                PlayerSource ps = (PlayerSource) list;
                if (ps.player != null) {
                    String name = ps.player.getCommandSenderName();
                    requester = "AE2CONTROLLER".equals(name) ? "Web Panel" : name;
                }
            } else if (e != null) {
                requester = "Machine (" + e.getClass().getSimpleName() + ")";
            } else if (list != null && list.isMachine()) {
                requester = "Machine";
            }
            IAEMixinCallbacks.getInstance()
                .jobStarted(
                    (ICraftingCPUCluster) (Object) instance,
                    (IAECraftingGrid) this,
                    (IAEGrid) grid,
                    isMerging,
                    !isMachine,
                    requester);
        }
        return link;
    }

    @Inject(
        method = "updatePatterns",
        at = @At(value = "INVOKE", target = "Ljava/util/Map;clear()V", ordinal = 0, shift = At.Shift.AFTER))
    void ae2webintegration$updatePatternsStart(CallbackInfo ci) {
        web$mediumToViewable.clear();
        web$currentCraftingProvider = null;
    }

    @Redirect(
        method = "updatePatterns",
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
        if (web$currentCraftingProvider instanceof IInterfaceViewable viewable
            && !web$mediumToViewable.containsKey(medium)) {
            web$mediumToViewable.put(medium, viewable);
        }
    }

    // --- ICraftingMediumTracker ---

    @Override
    public IPatternProviderViewable web$getViewableForCraftingMedium(ICraftingMediumKey medium) {
        return (IPatternProviderViewable) web$mediumToViewable.get((ICraftingMedium) medium);
    }

    // This overrides the default from AECraftingGridMixin (interface mixin on ICraftingGrid)
    public ICraftingMediumTracker web$getCraftingProviders() {
        return (ICraftingMediumTracker) this;
    }
}
