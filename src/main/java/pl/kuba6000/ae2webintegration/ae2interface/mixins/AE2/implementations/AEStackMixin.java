package pl.kuba6000.ae2webintegration.ae2interface.mixins.AE2.implementations;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameData;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.FluidCraftResources;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyIconBaseline;
import pl.kuba6000.ae2webintegration.ae2interface.legacy.LegacyItemIdentity;
import pl.kuba6000.ae2webintegration.core.api.ResourceType;
import pl.kuba6000.ae2webintegration.core.identity.StableKey;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGenericStack;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEGrid;
import pl.kuba6000.ae2webintegration.core.interfaces.IAEKey;

@Mixin(IAEStack.class)
public interface AEStackMixin extends IAEStack, IAEGenericStack, IAEKey {

    @Override
    default @NotNull ResourceType web$getResourceType() {
        if (this instanceof IAEFluidStack) return ResourceType.FLUID;
        if (this instanceof IAEItemStack item) {
            if (Loader.isModLoaded("ae2fc") && FluidCraftResources.isFluidDrop(item.getItem())) {
                return ResourceType.FLUID;
            }
            return ResourceType.ITEM;
        }
        return ResourceType.OTHER;
    }

    @Override
    default @NotNull StableKey web$getKey() {
        return LegacyItemIdentity.encode(this);
    }

    @Override
    default @Nullable StableKey web$getIconBaseKey() {
        IAEStack<?> stack = this;
        if (stack instanceof AEItemStack item) {
            // Native durability hooks must only see an owned representation, never shared AE NBT.
            return LegacyItemIdentity.encode(LegacyIconBaseline.item(item.getItemStack()));
        }
        if (stack instanceof AEFluidStack fluid) {
            return LegacyItemIdentity.encode(LegacyIconBaseline.fluid(fluid.getFluidStack()));
        }
        return null;
    }

    @Override
    default @NotNull IAEKey web$copyIdentity() {
        return (IAEKey) LegacyItemIdentity.copy(this);
    }

    @Override
    default @Nullable String web$getRegistryNamespace() {
        if (this instanceof IAEItemStack item && web$getResourceType() == ResourceType.ITEM) {
            String name = GameData.getItemRegistry()
                .getNameForObject(item.getItem());
            return name.substring(0, name.indexOf(':'));
        }
        Fluid fluid = null;
        if (this instanceof IAEFluidStack stack) fluid = stack.getFluid();
        else if (this instanceof IAEItemStack item && web$getResourceType() == ResourceType.FLUID) {
            fluid = FluidCraftResources.getFluid(item);
        }
        if (fluid == null) return null;
        String name = FluidRegistry.getDefaultFluidName(fluid);
        return name.substring(0, name.indexOf(':'));
    }

    @Override
    default @Nullable String web$getRegistryPath() {
        if (this instanceof IAEItemStack item && web$getResourceType() == ResourceType.ITEM) {
            String name = GameData.getItemRegistry()
                .getNameForObject(item.getItem());
            return name.substring(name.indexOf(':') + 1);
        }
        if (this instanceof IAEFluidStack stack) return stack.getFluid()
            .getName();
        if (this instanceof IAEItemStack item && web$getResourceType() == ResourceType.FLUID) {
            Fluid fluid = FluidCraftResources.getFluid(item);
            return fluid == null ? null : fluid.getName();
        }
        return null;
    }

    @Override
    default @Nullable Integer web$getComponentCount() {
        if (this instanceof IAEItemStack item && web$getResourceType() == ResourceType.FLUID) {
            return FluidCraftResources.getFluid(item) == null ? null : FluidCraftResources.getComponentCount(item);
        }
        if (!(this instanceof IAEItemStack) && !(this instanceof IAEFluidStack)) return null;
        NBTTagCompound tag = (NBTTagCompound) getTagCompound();
        return tag == null ? 0
            : tag.func_150296_c()
                .size();
    }

    @Override
    default @Nullable Integer web$getDamage() {
        return this instanceof IAEItemStack item && web$getResourceType() == ResourceType.ITEM ? item.getItemDamage()
            : null;
    }

    @Override
    default @NotNull String web$getDisplayName() {
        return getDisplayName();
    }

    @Override
    default @NotNull IAEKey web$what() {
        return (IAEKey) this;
    }

    @Override
    default long web$amount() {
        return getStackSize();
    }

    @Override
    default boolean web$isCraftable(IAEGrid grid) {
        return isCraftable();
    }

}
