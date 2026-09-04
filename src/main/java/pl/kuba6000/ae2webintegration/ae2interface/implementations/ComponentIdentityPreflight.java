package pl.kuba6000.ae2webintegration.ae2interface.implementations;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import pl.kuba6000.ae2webintegration.core.identity.IdentityLimitException;

/** Explicit supported native schemas: no arbitrary addon codec runs before a bounded preflight. */
final class ComponentIdentityPreflight {

    private ComponentIdentityPreflight() {}

    static void check(AEKey key, CanonicalNbt.Budget budget) throws IOException {
        DataComponentPatch patch;
        if (key instanceof AEItemKey item) {
            patch = item.getReadOnlyStack()
                .getComponentsPatch();
        } else if (key instanceof AEFluidKey fluid) {
            // FluidStack.copy uses PatchedDataComponentMap.copy: constant-size wrappers sharing a COW patch map.
            patch = fluid.toStack(1)
                .getComponentsPatch();
        } else {
            throw unsupported();
        }
        if (patch.size() > 256) throw new IdentityLimitException();
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet()) {
            DataComponentType<?> type = entry.getKey();
            ResourceLocation id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
            if (id == null || type.isTransient()) throw unsupported();
            budget.text(id.toString());
            budget.add(128);
            if (entry.getValue()
                .isEmpty()) continue;
            Object value = entry.getValue()
                .get();
            if (type == DataComponents.CUSTOM_DATA || type == DataComponents.ENTITY_DATA
                || type == DataComponents.BLOCK_ENTITY_DATA
                || type == DataComponents.BUCKET_ENTITY_DATA) {
                CanonicalNbt.measure(((CustomData) value).getUnsafe(), budget, 0);
            } else if (type == DataComponents.CUSTOM_NAME || type == DataComponents.ITEM_NAME) {
                text((Component) value, budget, 0);
            } else if (type == DataComponents.LORE) {
                ItemLore lore = (ItemLore) value;
                texts(lore.lines(), budget, 0);
                texts(lore.styledLines(), budget, 0);
            } else if (type == DataComponents.ENCHANTMENTS || type == DataComponents.STORED_ENCHANTMENTS) {
                ItemEnchantments enchantments = (ItemEnchantments) value;
                if (enchantments.getClass() != ItemEnchantments.class) throw unsupported();
                if (enchantments.size() > 256) throw new IdentityLimitException();
                for (Holder<?> enchantment : enchantments.keySet()) {
                    ResourceLocation name = enchantment.unwrapKey()
                        .orElseThrow(ComponentIdentityPreflight::unsupported)
                        .location();
                    budget.text(name.toString());
                    budget.add(32);
                }
            } else if (!scalar(type)) {
                throw unsupported();
            }
        }
    }

    private static boolean scalar(DataComponentType<?> type) {
        return type == DataComponents.MAX_STACK_SIZE || type == DataComponents.MAX_DAMAGE
            || type == DataComponents.DAMAGE
            || type == DataComponents.UNBREAKABLE
            || type == DataComponents.RARITY
            || type == DataComponents.CUSTOM_MODEL_DATA
            || type == DataComponents.HIDE_ADDITIONAL_TOOLTIP
            || type == DataComponents.HIDE_TOOLTIP
            || type == DataComponents.REPAIR_COST
            || type == DataComponents.ENCHANTMENT_GLINT_OVERRIDE
            || type == DataComponents.INTANGIBLE_PROJECTILE
            || type == DataComponents.FIRE_RESISTANT
            || type == DataComponents.DYED_COLOR
            || type == DataComponents.MAP_COLOR
            || type == DataComponents.MAP_ID;
    }

    private static void texts(List<Component> values, CanonicalNbt.Budget budget, int depth) throws IOException {
        if (values.size() > 256) throw new IdentityLimitException();
        for (Component value : values) text(value, budget, depth + 1);
    }

    private static void text(Component value, CanonicalNbt.Budget budget, int depth) throws IOException {
        budget.node(depth);
        // Reserve codec structure and JSON escaping before the flattened text codec allocates.
        budget.add(512);
        if (value.getClass() != MutableComponent.class) throw unsupported();
        ComponentContents contents = value.getContents();
        if (contents instanceof PlainTextContents.LiteralContents literal) {
            escaped(literal.text(), budget);
        } else if (contents == PlainTextContents.EMPTY) {
            // Empty text has no payload.
        } else if (contents.getClass() == TranslatableContents.class) {
            TranslatableContents translated = (TranslatableContents) contents;
            escaped(translated.getKey(), budget);
            if (translated.getFallback() != null) escaped(translated.getFallback(), budget);
            Object[] args = translated.getArgs();
            if (args.length > 256) throw new IdentityLimitException();
            for (Object arg : args) {
                if (arg instanceof Component component) text(component, budget, depth + 1);
                else if (arg instanceof String string) escaped(string, budget);
                else if (arg instanceof Integer || arg instanceof Long
                    || arg instanceof Float
                    || arg instanceof Double
                    || arg instanceof Byte
                    || arg instanceof Short
                    || arg instanceof Boolean) budget.add(64);
                else throw unsupported();
            }
        } else {
            throw unsupported();
        }
        Style style = value.getStyle();
        if (style.getClass() != Style.class || style.getHoverEvent() != null) throw unsupported();
        if (style.getInsertion() != null) escaped(style.getInsertion(), budget);
        if (style.getFont() != null) escaped(
            style.getFont()
                .toString(),
            budget);
        if (style.getClickEvent() != null) {
            if (style.getClickEvent()
                .getClass() != ClickEvent.class) throw unsupported();
            escaped(
                style.getClickEvent()
                    .getValue(),
                budget);
        }
        texts(value.getSiblings(), budget, depth);
    }

    private static void escaped(String value, CanonicalNbt.Budget budget) throws IOException {
        budget.text(value);
        budget.add(12L * value.length());
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Unsupported component identity schema");
    }
}
