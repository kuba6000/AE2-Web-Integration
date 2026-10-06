package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyIconBaseline;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyItemIdentity;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

@Mixin(value = IAEItemStack.class, remap = false)
public interface AEItemStackMixin extends IAEItemStack, IAEKey, IAEGenericStack {

    @Override
    default @NotNull ResourceType web$getResourceType() {
        return ResourceType.ITEM;
    }

    @Override
    default @NotNull StableKey web$getKey() {
        return LegacyItemIdentity.encode(this);
    }

    @Override
    default @Nullable StableKey web$getIconBaseKey() {
        IAEItemStack stack = this;
        if (!(stack instanceof AEItemStack nativeStack)) return null;
        Item item = nativeStack.getItem();
        int sourceMaxDamage = !item.getHasSubtypes() && item.isDamageable()
            ? item.getMaxDamage(nativeStack.asItemStackRepresentation())
            : 0;
        return LegacyItemIdentity.encode(LegacyIconBaseline.item(item, nativeStack.getItemDamage(), sourceMaxDamage));
    }

    @Override
    default @NotNull IAEKey web$copyIdentity() {
        return (IAEKey) LegacyItemIdentity.copy(this);
    }

    @Shadow
    Item getItem();

    @Shadow
    int getItemDamage();

    @Override
    default @NotNull String web$getRegistryNamespace() {
        return getItem().getRegistryName()
            .getNamespace();
    }

    @Override
    default @NotNull String web$getRegistryPath() {
        return getItem().getRegistryName()
            .getPath();
    }

    @Override
    default @NotNull Integer web$getComponentCount() {
        NBTTagCompound tag = getDefinition().getTagCompound();
        return tag == null ? 0 : tag.getSize();
    }

    @Override
    default @NotNull Integer web$getDamage() {
        return getItemDamage();
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
