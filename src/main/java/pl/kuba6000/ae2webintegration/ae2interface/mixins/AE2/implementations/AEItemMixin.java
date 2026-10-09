package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.me.Grid;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.NativeItemIdentity;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

@Mixin(value = AEKey.class, remap = false)
public abstract class AEItemMixin implements IAEKey {

    @Override
    public @NotNull ResourceType web$getResourceType() {
        AEKey key = (AEKey) (Object) this;
        if (key instanceof AEItemKey) return ResourceType.ITEM;
        if (key instanceof AEFluidKey) return ResourceType.FLUID;
        return ResourceType.OTHER;
    }

    @Override
    public @NotNull StableKey web$getKey() {
        return NativeItemIdentity.getKey((AEKey) (Object) this);
    }

    @Override
    public @Nullable StableKey web$getIconBaseKey() {
        AEKey key = (AEKey) (Object) this;
        if (!(key instanceof AEItemKey) && !(key instanceof AEFluidKey)) return null;
        AEKey baseline = key.dropSecondary();
        return baseline == null ? null : NativeItemIdentity.getKey(baseline);
    }

    @Override
    public @NotNull IAEKey web$copyIdentity() {
        return (IAEKey) NativeItemIdentity.copy((AEKey) (Object) this);
    }

    @Shadow
    public ResourceLocation getId() {
        throw new UnsupportedOperationException("Mixin failed to apply");
    }

    @Shadow
    public Component getDisplayName() {
        throw new UnsupportedOperationException("Mixin failed to apply");
    }

    @Override
    public @NotNull String web$getRegistryNamespace() {
        return getId().getNamespace();
    }

    @Override
    public @NotNull String web$getRegistryPath() {
        return getId().getPath();
    }

    @Override
    public int web$getComponentCount() {
        AEKey key = (AEKey) (Object) this;
        if (key instanceof AEItemKey item) return item.getReadOnlyStack()
            .getComponents()
            .size();
        return 0;
    }

    @Override
    public int web$getDamage() {
        AEKey key = (AEKey) (Object) this;
        return key instanceof AEItemKey item ? item.getReadOnlyStack()
            .getDamageValue() : 0;
    }

    @Override
    public @NotNull String web$getDisplayName() {
        return getDisplayName().getString();
    }

    @Override
    public boolean web$isCraftable(IAEGrid grid) {
        return ((Grid) grid).getCraftingService()
            .isCraftable((AEKey) (Object) this);
    }

}
