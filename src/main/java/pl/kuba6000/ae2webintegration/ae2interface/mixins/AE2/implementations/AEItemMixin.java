package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.api.stacks.AEKey;
import appeng.me.Grid;
import pl.kuba6000.ae2webintegration.ae2interface.implementations.NativeItemIdentity;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

@Mixin(value = AEKey.class, remap = false)
public abstract class AEItemMixin implements IAEKey {

    @Override
    public @NotNull StableKey web$getKey() {
        return NativeItemIdentity.getKey((AEKey) (Object) this);
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
    public @NotNull String web$getItemID() {
        ResourceLocation rs = getId();
        return rs.getNamespace() + ":" + rs.getPath();
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
