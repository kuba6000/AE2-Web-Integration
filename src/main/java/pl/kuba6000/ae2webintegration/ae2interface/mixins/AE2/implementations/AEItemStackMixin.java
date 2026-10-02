package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyItemIdentity;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

@Mixin(value = IAEItemStack.class, remap = false)
public interface AEItemStackMixin extends IAEItemStack, IAEKey, IAEGenericStack {

    @Override
    default @NotNull StableKey web$getKey() {
        return LegacyItemIdentity.encode(this);
    }

    @Override
    default @Nullable StableKey web$getIconBaseKey() {
        IAEItemStack stack = this;
        if (!(stack instanceof AEItemStack)) return null;
        Item item = getItem();
        // Fresh construction initializes default Forge capabilities without the original state.
        ItemStack baseline = new ItemStack(item, 1, getItemDamage());
        if (!item.getHasSubtypes() && item.isDamageable()
            && baseline.isItemStackDamageable()
            && item.getMaxDamage(baseline) == item.getMaxDamage(asItemStackRepresentation())) {
            baseline.setItemDamage(0);
        }
        AEItemStack key = AEItemStack.fromItemStack(baseline);
        return key == null ? null : LegacyItemIdentity.encode(key);
    }

    @Override
    default @NotNull IAEKey web$copyIdentity() {
        return LegacyItemIdentity.copy(this);
    }

    @Shadow
    Item getItem();

    @Shadow
    int getItemDamage();

    @Override
    default @NotNull String web$getItemID() {
        return getItem().getRegistryName() + ":" + getItemDamage();
    }

    @Override
    default @NotNull String web$getDisplayName() {
        return asItemStackRepresentation().getDisplayName();
    }

    @Override
    default boolean web$isCraftable(IAEGrid grid) {
        return isCraftable();
    }

    @Override
    default @NotNull IAEKey web$what() {
        return this;
    }

    @Override
    default long web$amount() {
        return getStackSize();
    }

}
